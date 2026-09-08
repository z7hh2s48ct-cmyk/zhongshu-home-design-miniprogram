package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxDispatcherService;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

/**
 * 最小生产驱动器（审查 C2）：把「隔离校验 → 槽位结算 → 候选晋升 → Outbox 投递 → 支付补偿」串成定时轮。
 *
 * P4D 真实 Runtime 就绪前的过渡方案：Runtime 缺席时由本驱动器以「无结果」路径驱动任务到终态，
 * 保证扣点任务不悬挂；P4D 接入后 Runtime 的 claim/回写照常工作，本驱动器只承担校验/结算/晋升/投递尾程。
 *
 * 开关：zhongshu.design.driver-enabled（默认 false，zsdev profile 打开）。
 */
@Slf4j
@Component
public class ZhongshuJobDriver {

    private final JdbcTemplate jdbcTemplate;
    private final AiJobOrchestrationService orchestration;
    private final AiJobSettlementService settlement;
    private final DesignProjectService designProjects;
    private final OutboxDispatcherService dispatcher;
    private final RechargePaymentService payment;

    @Value("${zhongshu.design.driver-enabled:false}")
    private boolean enabled;

    @Value("${zhongshu.design.cancel-grace-minutes:10}")
    private int cancelGraceMinutes;

    @Value("${zhongshu.design.no-runtime-grace-minutes:30}")
    private int noRuntimeGraceMinutes;

    public ZhongshuJobDriver(DataSource dataSource, AiJobOrchestrationService orchestration,
                             AiJobSettlementService settlement, DesignProjectService designProjects,
                             OutboxDispatcherService dispatcher, RechargePaymentService payment) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.orchestration = orchestration;
        this.settlement = settlement;
        this.designProjects = designProjects;
        this.dispatcher = dispatcher;
        this.payment = payment;
    }

    /** 主循环：校验 → 结算 → 晋升 → 投递 → 支付补偿，每 10 秒一轮 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 20_000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int validated = validateQuarantined();
            int settled = settleReadyJobs();
            int promoted = promoteSettledCandidates();
            int dispatched = dispatcher.dispatchOnce("zs-driver", "driver-1", 30, 50, 60);
            dispatcher.heartbeat("zs-driver", "driver-1", 60);
            int recovered = payment.recoverHangingOrders();
            int inboxRecovered = payment.recoverPendingInbox();
            if (validated + settled + promoted + dispatched + recovered + inboxRecovered > 0) {
                log.info("[tick][validated={} settled={} promoted={} dispatched={} payRecovered={} inboxRecovered={}]",
                        validated, settled, promoted, dispatched, recovered, inboxRecovered);
            }
        } catch (Exception e) {
            log.warn("[tick][驱动轮失败，下轮重试: {}]", e.getMessage());
        }
    }

    /** 对有隔离结果的 RUNNING/VALIDATING/CANCEL_REQUESTED 任务做校验 */
    private int validateQuarantined() {
        List<Long> jobIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT job_id FROM ai_job_result WHERE validation_state = 'OUTPUT_QUARANTINED' "
                        + "AND job_id IN (SELECT id FROM ai_job WHERE status IN "
                        + "('RUNNING','VALIDATING','CANCEL_REQUESTED')) LIMIT 20",
                Long.class);
        int total = 0;
        for (Long jobId : jobIds) {
            total += orchestration.validateQuarantinedResults(jobId);
        }
        return total;
    }

    /** 对可结算任务执行槽位结算（0 有效=FAILED 全退、部分=差额退、N=SUCCEEDED；取消路径同规则） */
    private int settleReadyJobs() {
        List<Map<String, Object>> ready = jdbcTemplate.queryForList(
                "SELECT id, status FROM ai_job WHERE status IN ('RUNNING','VALIDATING','CANCEL_REQUESTED') "
                        + "AND deleted = FALSE AND ("
                        // 已有 ACCEPTED 结果即结算
                        + "  EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id = ai_job.id "
                        + "          AND r.validation_state = 'ACCEPTED')"
                        // 或取消宽限到期（P4D 前无 Runtime 产出，走此路径全退）
                        + "  OR (status = 'CANCEL_REQUESTED' AND cancel_requested_at < "
                        + "      now() - (? * interval '1 minute'))"
                        // 或运行超时无 Runtime（宽限期后判 FAILED 全退，避免任务永久悬挂）
                        + "  OR (status = 'RUNNING' AND update_time < now() - (? * interval '1 minute'))"
                        + ") ORDER BY id LIMIT 20",
                cancelGraceMinutes, noRuntimeGraceMinutes);
        int settled = 0;
        for (Map<String, Object> job : ready) {
            long jobId = ((Number) job.get("id")).longValue();
            String status = (String) job.get("status");
            try {
                var summary = settlement.settle(jobId, "CANCEL_REQUESTED".equals(status) ? "CANCEL" : "SETTLE");
                settled++;
                log.info("[settleReady][job={} {} → {} refund={}]",
                        jobId, status, summary.status(), summary.refundedNow());
            } catch (Exception e) {
                log.warn("[settleReady][job={} 结算失败: {}]", jobId, e.getMessage());
            }
        }
        return settled;
    }

    /**
     * 候选自动晋升（C2）：已结算且带 ACCEPTED 结果、但尚未落候选的任务，
     * 直接晋升为资产+候选（幂等），不再依赖前端查询触发。只回扫近 24 小时，
     * 走索引范围扫描；晋升失败仅告警，由拉式路径（GET design-projects?jobId=）兜底。
     */
    private int promoteSettledCandidates() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                "SELECT j.id, j.user_id, j.project_ref FROM ai_job j "
                        + "WHERE j.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') AND j.deleted = FALSE "
                        + "AND EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id = j.id "
                        + "            AND r.validation_state = 'ACCEPTED') "
                        + "AND NOT EXISTS (SELECT 1 FROM design_candidate c WHERE c.job_id = j.id "
                        + "            AND c.deleted = FALSE) "
                        + "AND j.update_time > now() - interval '24 hours' ORDER BY j.id LIMIT 20");
        int promoted = 0;
        for (Map<String, Object> job : pending) {
            long jobId = ((Number) job.get("id")).longValue();
            long userId = ((Number) job.get("user_id")).longValue();
            try {
                long projectId = Long.parseLong(String.valueOf(job.get("project_ref")));
                designProjects.promoteCandidatesForSettledJob(userId, projectId, jobId);
                promoted++;
                log.info("[promoteSettled][job={} project={} 候选已晋升]", jobId, projectId);
            } catch (Exception e) {
                log.warn("[promoteSettled][job={} 晋升失败: {}]", jobId, e.getMessage());
            }
        }
        return promoted;
    }

}
