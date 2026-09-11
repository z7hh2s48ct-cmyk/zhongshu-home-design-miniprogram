package cn.iocoder.yudao.module.commerce.payment;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.PAYMENT_ORDER_STATE_CONFLICT;

/**
 * T13-27：支付事实校验器（分派表 §3.2 ①）。
 *
 * <p>在 {@link RechargePaymentService#processPaymentFact} 之前执行前置校验，
 * 所有异常（硬拒绝 / 软告警）均落 {@code payment_anomaly_audit} 表（分派表 §3.2 ②），
 * 禁止静默吞掉。
 *
 * <p><b>校验项</b>：
 * <ul>
 *   <li>transactionId 非空 → REJECT（渠道交易号是幂等键的一部分）</li>
 *   <li>金额一致性（通知金额 vs 订单快照）→ REJECT（分派表 §3.2 ①）</li>
 *   <li>缺实付金额 → AUDIT + 回退声明值（分派表 §3.2 ④）</li>
 *   <li>paidAt 为空 → AUDIT（使用当前时间回退）</li>
 * </ul>
 *
 * <p><b>事务边界</b>：审计写入使用独立 auto-commit，调用点（{@code handleNotification} Phase 2 前 /
 * {@code reconcile} 推进前）均无活动事务（两者内部各自持有 {@code txTemplate}），
 * 因此硬拒绝抛异常后审计记录仍持久化，不随主事务回滚。
 */
@Slf4j
@Component
public class PaymentFactValidator {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentPort paymentPort;

    /**
     * @param dataSource 数据源。与 {@link RechargePaymentService}、{@code CaseCatalogService} 等保持一致：
     *                   注入 {@code DataSource} 后自建 {@link JdbcTemplate}，不依赖 Spring Boot 的
     *                   {@code JdbcTemplateAutoConfiguration} 是否生效，避免真实模式下 no-bean 装配失败。
     */
    public PaymentFactValidator(DataSource dataSource, PaymentPort paymentPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.paymentPort = paymentPort;
    }

    /**
     * 校验支付事实并记录异常。
     *
     * @param orderNo             本地订单号
     * @param eventId             通知事件 ID（可为 null，如 reconcile 路径）
     * @param channelTransactionId 渠道交易号
     * @param declaredAmountCents 订单快照金额（分）
     * @param channelAmountCents  渠道返回实付金额（分）；null 表示渠道未提供
     * @param paidAt              支付时间；null 表示渠道未提供
     * @return 有效金额（分）：channelAmountCents 非 null 时取渠道值，否则回退声明值
     * @throws ServiceException 硬拒绝（transactionId 缺失 / 金额不符）
     */
    public long validateAndAudit(String orderNo, String eventId, String channelTransactionId,
                                 long declaredAmountCents, Long channelAmountCents, Instant paidAt) {
        // 1. transactionId 非空（REJECT）
        if (channelTransactionId == null || channelTransactionId.isBlank()) {
            recordAnomaly(orderNo, eventId, "TRANSACTION_ID_MISSING", "REJECT",
                    "non-blank", String.valueOf(channelTransactionId), "渠道交易号为空，无法建立幂等映射");
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }

        // 2. 缺实付金额 → AUDIT + 回退声明值（分派表 §3.2 ④）
        long effectiveAmount;
        if (channelAmountCents == null) {
            effectiveAmount = declaredAmountCents;
            recordAnomaly(orderNo, eventId, "CHANNEL_AMOUNT_FALLBACK", "AUDIT",
                    String.valueOf(declaredAmountCents), null,
                    "渠道未返回实付金额，回退订单声明值");
        } else {
            effectiveAmount = channelAmountCents;
        }

        // 3. 金额一致性（REJECT）：通知金额 vs 订单快照
        if (effectiveAmount != declaredAmountCents) {
            recordAnomaly(orderNo, eventId, "AMOUNT_MISMATCH", "REJECT",
                    String.valueOf(declaredAmountCents), String.valueOf(effectiveAmount),
                    "通知金额与订单快照不符，拒绝推进支付事实");
            throw exception(PAYMENT_ORDER_STATE_CONFLICT);
        }

        // 4. paidAt 为空 → AUDIT（使用当前时间回退，不拒绝）
        if (paidAt == null) {
            recordAnomaly(orderNo, eventId, "PAID_AT_MISSING", "AUDIT",
                    "non-null", null, "渠道未返回支付时间，使用当前时间回退");
        }

        return effectiveAmount;
    }

    /**
     * T13-28：对外审计入口，供退款通知等非「支付事实」路径复用同一张 {@code payment_anomaly_audit} 表，
     * 避免审计写入逻辑散落到多个服务。语义与内部 {@link #recordAnomaly} 一致（写失败降级为日志，不阻断主流程）。
     */
    public void audit(String orderNo, String eventId, String anomalyType, String severity,
                      String expected, String actual, String detail) {
        recordAnomaly(orderNo, eventId, anomalyType, severity, expected, actual, detail);
    }

    /**
     * 写入异常审计记录（独立 auto-commit，不在主事务内）。
     */
    private void recordAnomaly(String orderNo, String eventId, String anomalyType,
                               String severity, String expected, String actual, String detail) {
        try {
            jdbcTemplate.update(
                    "INSERT INTO payment_anomaly_audit (id, order_no, event_id, anomaly_type, severity, "
                            + "expected_value, actual_value, detail, channel) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    IdWorker.getId(), orderNo, eventId, anomalyType, severity,
                    expected, actual, detail, paymentPort.channel());
            log.warn("[PaymentFactValidator] 异常审计: order={}, type={}, severity={}, detail={}",
                    orderNo, anomalyType, severity, detail);
        } catch (Exception e) {
            // 审计写入失败不应阻断主流程（降级为日志）
            log.error("[PaymentFactValidator] 审计写入失败: order={}, type={}", orderNo, anomalyType, e);
        }
    }
}
