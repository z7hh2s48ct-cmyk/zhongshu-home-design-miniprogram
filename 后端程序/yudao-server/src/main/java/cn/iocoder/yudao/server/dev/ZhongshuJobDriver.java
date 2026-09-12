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
 * Runtime 领取并回写结果；本驱动器负责校验、完成屏障后的结算、晋升及可靠投递。
 * Runtime 缺席或进程失联时，按任务创建时间计算硬截止，结算已接受结果并退还剩余设计点。
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
    public void tick() {
        if (!enabled) {
            return;
        }
        step("validate", this::validateQuarantined);
        step("settle", this::settleReadyJobs);
        step("promote", this::promoteSettledCandidates);
        step("outbox", () -> { int count=dispatcher.dispatchOnce("zs-driver", workerId, 30, 50, 60); dispatcher.heartbeat("zs-driver", workerId, 60); return count; });
        step("payment", payment::recoverHangingOrders);
        step("inbox", payment::recoverPendingInbox);
    }

    private final String workerId = "driver-" + java.util.UUID.randomUUID();
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void validationTick() { if(enabled) step("validate",this::validateQuarantined); }
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void settlementTick() { if(enabled) step("settle",this::settleReadyJobs); }
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void promotionTick() { if(enabled) step("promote",this::promoteSettledCandidates); }
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void outboxTick() { if(enabled) step("outbox",()->{int count=dispatcher.dispatchOnce("zs-driver",workerId,30,50,60);dispatcher.heartbeat("zs-driver",workerId,60);return count;}); }
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void paymentTick() { if(enabled) step("payment",payment::recoverHangingOrders); }
    @Scheduled(fixedDelay=10000, initialDelay=20000) public void paymentInboxTick() { if(enabled) step("inbox",payment::recoverPendingInbox); }
    private void step(String name, java.util.function.IntSupplier operation) {
        try { int count=operation.getAsInt(); if (count>0) log.info("[driver][stage={} processed={}]", name,count); }
        catch (Exception e) { log.warn("[driver][stage={} failureType={}]", name,e.getClass().getSimpleName()); }
    }

    /** 对有隔离结果的 RUNNING/VALIDATING/CANCEL_REQUESTED 任务做校验 */
    private int validateQuarantined() {
        List<Long> jobIds = jdbcTemplate.queryForList(
                "SELECT DISTINCT job_id FROM ai_job_result WHERE validation_state = 'OUTPUT_QUARANTINED' "
                        + "AND job_id IN (SELECT id FROM ai_job WHERE status IN "
                        + "('RUNNING','VALIDATING','CANCEL_REQUESTED')) AND validation_due_at<=now() ORDER BY job_id LIMIT 20",
                Long.class);
        int total = 0;
        for (Long jobId : jobIds) {
            try { total += orchestration.validateQuarantinedResults(jobId); }
            catch (Exception e) { log.warn("[validate][job={} failureType={}]",jobId,e.getClass().getSimpleName()); }
        }
        return total;
    }

    /** 对可结算任务执行槽位结算（0 有效=FAILED 全退、部分=差额退、N=SUCCEEDED；取消路径同规则） */
    private int settleReadyJobs() {
        List<Map<String, Object>> ready = jdbcTemplate.queryForList(
                "SELECT id, status FROM ai_job WHERE status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') "
                        + "AND deleted = FALSE AND ("
                        // 全部槽位已接受，或 Runtime 已完成且没有待校验结果
                        + "  (SELECT count(*) FROM ai_job_result r WHERE r.job_id = ai_job.id AND r.validation_state = 'ACCEPTED') >= requested_count"
                        + " OR (runtime_completed_at IS NOT NULL AND NOT EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id=ai_job.id AND r.validation_state='OUTPUT_QUARANTINED'))"
                        // 或取消宽限到期，按已接受槽位结算
                        + "  OR (status = 'CANCEL_REQUESTED' AND cancel_requested_at < "
                        + "      now() - (? * interval '1 minute'))"
                        // 或达到从创建时间计算的硬截止，不被心跳或校验更新时间延长
                        + "  OR (create_time < now() - (? * interval '1 minute'))"
                        + ") ORDER BY create_time,id LIMIT 20",
                cancelGraceMinutes, noRuntimeGraceMinutes);
        int settled = 0;
        for (Map<String, Object> job : ready) {
            long jobId = ((Number) job.get("id")).longValue();
            String status = (String) job.get("status");
            try {
                if (settlement.settleIfReady(jobId,noRuntimeGraceMinutes,cancelGraceMinutes)) settled++;
            } catch (Exception e) {
                log.warn("[settleReady][job={} failureType={}]", jobId, e.getClass().getSimpleName());
            }
        }
        return settled;
    }

    /**
     * 候选自动晋升（C2）：已结算且带 ACCEPTED 结果、但尚未落候选的任务，
     * 直接晋升为资产+候选（幂等）。不限历史窗口，失败记录轮转到队尾，
     * 后续批次重试；历史隔离区旧结果必须先重新校验才可晋升。
     */
    private int promoteSettledCandidates() {
        List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                "SELECT j.id, j.user_id, j.project_ref FROM ai_job j "
                        + "WHERE j.status IN ('SUCCEEDED','PARTIALLY_SUCCEEDED') AND j.deleted = FALSE "
                        + "AND EXISTS (SELECT 1 FROM ai_job_result r WHERE r.job_id = j.id "
                        + "            AND r.validation_state = 'ACCEPTED') "
                        + "AND (SELECT count(*) FROM design_candidate c WHERE c.job_id=j.id AND c.deleted=FALSE) < "
                        + "(SELECT count(*) FROM ai_job_result r WHERE r.job_id=j.id AND r.validation_state='ACCEPTED') "
                        + "ORDER BY j.update_time,j.id LIMIT 20");
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
                jdbcTemplate.update("UPDATE ai_job SET update_time=now() WHERE id=?",jobId);
                log.warn("[promoteSettled][job={} failureType={}]", jobId, e.getClass().getSimpleName());
            }
        }
        return promoted;
    }

}
