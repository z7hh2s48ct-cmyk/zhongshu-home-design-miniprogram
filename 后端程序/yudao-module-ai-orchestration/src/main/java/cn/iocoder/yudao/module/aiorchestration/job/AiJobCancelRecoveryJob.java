package cn.iocoder.yudao.module.aiorchestration.job;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 任务取消收敛调度器（测试报告 15 P2-D）。
 *
 * <p>修复「取消请求后无人收口」的资损缺陷：requestCancellation 会把 QUEUED 任务也置为
 * CANCEL_REQUESTED，但 claim 只领取 QUEUED/租约过期的 RUNNING——被取消的任务永远不会再被
 * 领取，而 completeCancellation/settleIfReady 此前只有单元测试调用，生产代码没有任何驱动，
 * 导致任务永久停在 CANCEL_REQUESTED、已扣出图点永不退还。
 *
 * <p>两条收口路径（均落到 {@link AiJobSettlementService} 的既有事务链，幂等）：
 * <ul>
 *   <li><b>未领取即时结算</b>：{@code CANCEL_REQUESTED 且从未被领取}（runtime_completed_at 为空、
 *       claimed_by 为空）的任务不可能再产生任何结果，立即 settle(CANCEL) 全额退还；</li>
 *   <li><b>已领取超时兜底</b>：被领取后引擎失联的任务按 cancel 超时窗（默认 10 分钟）走
 *       {@link AiJobSettlementService#settleIfReady}，与任务级 deadline 兜底共用同一白名单。</li>
 * </ul>
 *
 * <p>开关 {@code zhongshu.ai.job-cancel-recovery-enabled}（默认 false，zsdev/生产 profile 打开），
 * 与 RefundRecoveryJob 的开关语义一致：未显式开启时不跑任何后台资金动作。
 */
@Slf4j
@Component
public class AiJobCancelRecoveryJob {

    private final AiJobSettlementService settlement;
    private final JdbcTemplate jdbcTemplate;

    @Value("${zhongshu.ai.job-cancel-recovery-enabled:false}")
    private boolean enabled;

    @Value("${zhongshu.ai.job-deadline-minutes:30}")
    private int deadlineMinutes;

    @Value("${zhongshu.ai.job-cancel-minutes:10}")
    private int cancelMinutes;

    public AiJobCancelRecoveryJob(AiJobSettlementService settlement, JdbcTemplate jdbcTemplate) {
        this.settlement = settlement;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(fixedDelayString = "${zhongshu.ai.job-cancel-sweep-interval-ms:15000}")
    public void sweep() {
        if (!enabled) {
            return;
        }
        try {
            // ① 未领取的取消：不存在在途结果，立即结算退款（settle 内部白名单校验状态，天然幂等）
            List<Long> unclaimed = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_job WHERE deleted=FALSE AND status='CANCEL_REQUESTED' "
                            + "AND runtime_completed_at IS NULL AND claimed_by IS NULL "
                            + "ORDER BY cancel_requested_at LIMIT 50", Long.class);
            for (Long jobId : unclaimed) {
                try {
                    AiJobSettlementService.SettlementSummary summary = settlement.settle(jobId, "CANCEL");
                    log.info("[AiJobCancelRecoveryJob][未领取取消已结算 jobId={} status={} refund={}]", jobId, summary.status(), summary.refundedNow());
                } catch (Exception e) {
                    log.warn("[AiJobCancelRecoveryJob][未领取取消结算失败 jobId={} 下轮重试: {}]", jobId, e.getMessage());
                }
            }
            // ② 已领取但引擎失联的取消：超过取消窗后由 settleIfReady 兜底结算
            List<Long> stale = jdbcTemplate.queryForList(
                    "SELECT id FROM ai_job WHERE deleted=FALSE AND status='CANCEL_REQUESTED' "
                            + "AND cancel_requested_at < now() - (? * interval '1 minute') "
                            + "ORDER BY cancel_requested_at LIMIT 50", Long.class, cancelMinutes);
            for (Long jobId : stale) {
                try {
                    if (settlement.settleIfReady(jobId, deadlineMinutes, cancelMinutes)) {
                        log.info("[AiJobCancelRecoveryJob][超时取消已兜底结算 jobId={}]", jobId);
                    }
                } catch (Exception e) {
                    log.warn("[AiJobCancelRecoveryJob][超时取消兜底失败 jobId={} 下轮重试: {}]", jobId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[AiJobCancelRecoveryJob][扫描失败: {}]", e.getMessage());
        }
    }
}
