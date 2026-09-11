package cn.iocoder.yudao.module.commerce.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 退款收口调度器（分派表 §3.2 T13-29 ②④）。
 *
 * <p>承担退款「未知状态」的两条补偿路径，二者都最终落到渠道查单，绝不把
 * {@code PROCESSING}/{@code UNKNOWN} 当成成功或失败（分派表 §8 红线 5）：
 * <ul>
 *   <li><b>重启补偿</b>：{@link ApplicationReadyEvent} 就绪后调用一次
 *       {@link RechargePaymentService#recoverPendingRefunds()}，重驱进程崩溃/重启期间悬挂的退款单，
 *       并对长时间卡单落 {@code REFUND_STUCK} 审计；</li>
 *   <li><b>定时收口</b>：{@link Scheduled} 周期调用 {@link RechargePaymentService#reconcileRefunds()}，
 *       扫 {@code channel_state IN ('PROCESSING','UNKNOWN','CREATED')} 的退款单查单直到终态
 *       （SUCCEEDED→冲正 / FAILED→释放预留）。</li>
 * </ul>
 *
 * <p>开关 {@code zhongshu.design.refund-recovery-enabled}（默认 false，zsdev/生产 profile 打开），
 * 与 {@code ZhongshuJobDriver} 的 {@code driver-enabled} 一致：未显式开启时不跑任何后台资金动作。
 * 渠道请求超时不在此重发 refund——退款单号稳定（{@code refund-<refundId>}）由渠道幂等去重，
 * 本调度器只做「查单」不做「重新发起退款」（分派表 §3.2 T13-29 ⑤）。
 */
@Slf4j
@Component
public class RefundRecoveryJob {

    private final RechargePaymentService payment;

    @Value("${zhongshu.design.refund-recovery-enabled:false}")
    private boolean enabled;

    public RefundRecoveryJob(RechargePaymentService payment) {
        this.payment = payment;
    }

    /**
     * 重启补偿：应用就绪后立即重驱所有未终态退款单（分派表 §3.2 T13-29 ④）。
     * 失败仅告警，由后续 {@link #tick()} 定时轮兜底重试。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        if (!enabled) {
            return;
        }
        try {
            int resolved = payment.recoverPendingRefunds();
            log.info("[RefundRecoveryJob][重启补偿完成 resolved={}]", resolved);
        } catch (Exception e) {
            log.warn("[RefundRecoveryJob][重启补偿失败，下轮定时重试: {}]", e.getMessage());
        }
    }

    /**
     * 定时查单收口：周期扫描 {@code PROCESSING}/{@code UNKNOWN} 退款单直到终态（分派表 §3.2 T13-29 ②）。
     * 间隔/首次延迟可由 {@code zhongshu.design.refund-recovery-interval-ms} 与
     * {@code refund-recovery-initial-delay-ms} 配置（默认 60s / 30s）。
     */
    @Scheduled(fixedDelayString = "${zhongshu.design.refund-recovery-interval-ms:60000}",
            initialDelayString = "${zhongshu.design.refund-recovery-initial-delay-ms:30000}")
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            int resolved = payment.reconcileRefunds();
            if (resolved > 0) {
                log.info("[RefundRecoveryJob][定时收口 resolved={}]", resolved);
            }
        } catch (Exception e) {
            log.warn("[RefundRecoveryJob][定时收口失败，下轮重试: {}]", e.getMessage());
        }
    }
}
