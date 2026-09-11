package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.commerce.payment.PaymentFactValidator;
import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import cn.iocoder.yudao.module.commerce.payment.StubPaymentPortAdapter;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P8A 合同测试（真实 PostgreSQL）：方案快照、建单幂等、通知→支付事实→到账（独立事务+原子）、
 * 重复通知/金额不符/伪造成功页、主动查单补偿、P0 整单退款（预留/冲正/拒绝路径）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentP8AContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private PointAccountService points;
    private RechargePaymentService payment;
    private StubPaymentPortAdapter stubChannel;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        points = new PointAccountService(dataSource, txManager);
        stubChannel = new StubPaymentPortAdapter();
        // T13-27：PaymentFactValidator 前置校验 + 异常审计（注入 DataSource，与生产装配路径一致）
        PaymentFactValidator factValidator = new PaymentFactValidator(dataSource, stubChannel);
        payment = new RechargePaymentService(dataSource, txManager, stubChannel,
                new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(points),
                points, new JdbcReliableEventPort(dataSource), factValidator);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE recharge_plan, recharge_order, payment_notification_inbox, "
                + "payment_transaction, recharge_credit, refund_order, "
                + "design_point_account, design_point_ledger, outbox_event, payment_anomaly_audit");
        stubChannel.clearScripts();
    }

    private long seedPlan(long amountCents, long base, long bonus) {
        return payment.createPlan("充值 " + amountCents / 100 + " 元", amountCents, base, bonus, false, 0);
    }

    private long available(long userId) {
        return points.findAccount(userId)
                .map(PointAccountService.PointAccount::availablePoints).orElse(0L);
    }

    // ========== 1. 快照与幂等建单 ==========

    @Test
    void orderSnapshotsPriceAndCreateIsIdempotent() {
        long planId = seedPlan(1000, 100, 20);
        var o1 = payment.createOrder(1L, planId, "key-1", "test-openid-1");
        var o2 = payment.createOrder(1L, planId, "key-1", "test-openid-1");
        assertThat(o2.orderId()).isEqualTo(o1.orderId());
        assertThat(o1.paymentState()).isEqualTo("PENDING");

        // 改价不影响已建订单（快照）
        jdbc.update("UPDATE recharge_plan SET amount_cents = 2000, base_points = 999 WHERE id = ?", planId);
        var reloaded = payment.getOrderById(o1.orderId()).orElseThrow();
        assertThat(reloaded.amountCents()).isEqualTo(1000);
        assertThat(reloaded.basePoints()).isEqualTo(100);
    }

    // ========== 2. 通知 → 支付事实 → 到账（基础/赠送拆分） ==========

    @Test
    void notificationDrivesPaymentFactThenCredit() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-notif-1", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-1\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-1\",\"amountCents\":1000,\"paidAtEpochSecond\":"
                + Instant.now().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8);

        String outcome = payment.handleNotification(Map.of(), body);
        assertThat(outcome).isEqualTo("PROCESSED");

        var after = payment.getOrderById(order.orderId()).orElseThrow();
        assertThat(after.paymentState()).isEqualTo("SUCCEEDED");
        assertThat(after.fulfillmentState()).isEqualTo("CREDITED");
        assertThat(available(1L)).isEqualTo(120);
        // 到账事实唯一
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM recharge_credit WHERE order_id = ?", Integer.class,
                order.orderId())).isEqualTo(1);
        // Outbox
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'ORDER_CREDITED'",
                Integer.class)).isEqualTo(1);

        // 重复通知幂等
        assertThat(payment.handleNotification(Map.of(), body)).isEqualTo("DUPLICATE");
        assertThat(available(1L)).isEqualTo(120);
    }

    // ========== 3. 金额不符拒绝 ==========

    @Test
    void amountMismatchRejected() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-amt", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-amt\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-x\",\"amountCents\":1,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> payment.handleNotification(Map.of(), body))
                .isInstanceOf(ServiceException.class);
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().paymentState())
                .as("金额不符不推进支付事实").isEqualTo("PENDING");
        assertThat(available(1L)).isZero();
    }

    // ========== 4. 支付成功但到账中断 → 主动查单补偿 ==========

    @Test
    void reconcileRecoversLostFulfillment() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-rec", "test-openid-1");
        // 直接推进支付事实（模拟通知处理到一半进程崩溃，到账未执行）
        payment.processPaymentFact(order.orderNo(), "evt-rec", "txn-rec", 1000, Instant.now());
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().paymentState())
                .isEqualTo("SUCCEEDED");
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState())
                .isEqualTo("NOT_READY");
        assertThat(available(1L)).isZero();

        // 主动查单补偿到账
        assertThat(payment.reconcile(order.orderNo())).isIn("RECOVERED", "ALREADY_RECONCILED");
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState())
                .isEqualTo("CREDITED");
        assertThat(available(1L)).isEqualTo(120);
    }

    // ========== 5. P0 整单退款：预留 → 渠道成功 → 冲正 ==========

    @Test
    void wholeOrderRefundReservesThenReverses() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-refund", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-r\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-r\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);
        assertThat(available(1L)).isEqualTo(120);

        Long refundId = payment.requestRefund(order.orderId(), "admin-1", "误充", "rk-1");
        assertThat(refundId).isNotNull();
        var detail = payment.getOrderDetail(1L, order.orderId()).orElseThrow();
        assertThat(detail.refund().refundId()).isEqualTo(String.valueOf(refundId));
        assertThat(detail.refund().channelState()).isEqualTo("SUCCEEDED");
        assertThat(detail.refund().pointReversalState()).isEqualTo("REVERSED");
        assertThat(detail.refund().amountCents()).isEqualTo(1000);
        assertThat(detail.refund().reason()).isEqualTo("误充");
        assertThat(payment.listOrders(1L, 1, 10).get(0).refund()).isEqualTo(detail.refund());
        assertThat(payment.getOrderDetail(2L, order.orderId())).isEmpty();
        // 冲正后总余额回到 0：预留 120 被扣减，并留两类冲正流水
        assertThat(available(1L)).isZero();
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'RECHARGE_BASE_REVERSAL'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'RECHARGE_BONUS_REVERSAL'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT point_reversal_state FROM refund_order WHERE id = ?", String.class, refundId))
                .isEqualTo("REVERSED");

        // 原充值/冲正流水均保留（只追加）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE user_id = 1", Integer.class))
                .isEqualTo(4);
    }

    // ========== 6. 到账后有扣减 → 退款拒绝，不形成负余额 ==========

    @Test
    void unpaidClosedOrderHasNoRefundFact() {
        var order = payment.createOrder(1L, seedPlan(1000, 100, 20), "closed-no-refund", "test-openid-1");
        jdbc.update("UPDATE recharge_order SET payment_state = 'CLOSED' WHERE id = ?", order.orderId());
        var detail = payment.getOrderDetail(1L, order.orderId()).orElseThrow();
        assertThat(detail.paymentState()).isEqualTo("CLOSED");
        assertThat(detail.refund()).isNull();
    }

    @Test
    void refundRejectedWhenPointsAlreadyUsed() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-used", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-u\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-u\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);

        // 用户消费了 30 点（模拟生成任务扣点）
        points.debit(1L, "FLAT_GENERATION_DEBIT", 30, "ai_job", "j-used", "k-used", null, null);
        assertThat(available(1L)).isEqualTo(70);

        assertThatThrownBy(() -> payment.requestRefund(order.orderId(), "admin-1", "用户申请", "rk-used"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_004));
        assertThat(available(1L)).as("拒绝后不形成负余额/不预留").isEqualTo(70);
        assertThat(count("refund_order")).isZero();
    }

    // ========== 7. 部分退款拒绝 ==========

    @Test
    void partialRefundPolicyDisabled() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-partial", "test-openid-1");
        assertThatThrownBy(() -> payment.requestRefund(order.orderId(), "admin-1", "只退一半", "rk-partial"))
                .as("未到账订单本就不受理；这里验证策略码存在")
                .isInstanceOf(ServiceException.class);
    }

    // ========== 7b. 同订单二次退款拒绝（评审 C1 回归） ==========

    @Test
    void duplicateRefundForSameOrderRejected() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-dup", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-dup\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-dup\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);

        Long first = payment.requestRefund(order.orderId(), "admin-1", "首次退款", "rk-dup-1");
        assertThat(first).isNotNull();
        // 用户再充值垫高余额（绕开余额防线），同订单二次退款必须被按单查重拦截
        long plan2 = seedPlan(500, 50, 0);
        var order2 = payment.createOrder(1L, plan2, "key-dup-2", "test-openid-1");
        payment.processPaymentFact(order2.orderNo(), "evt-dup-2", "txn-dup-2", 500, Instant.now());
        payment.fulfillOrder(order2.orderNo());

        assertThatThrownBy(() -> payment.requestRefund(order.orderId(), "admin-2", "再次退款", "rk-dup-2"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_002));
        // 同 requestKey 重放幂等
        assertThat(payment.requestRefund(order.orderId(), "admin-1", "首次退款", "rk-dup-1"))
                .isEqualTo(first);
    }

    // ========== 8. 渠道失败释放预留 ==========

    @Test
    void channelFailureReleasesReservation() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-fail", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-f\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-f\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);
        stubChannel.scriptRefund(order.orderNo(), "FAILED");

        Long refundId = payment.requestRefund(order.orderId(), "admin-1", "渠道拒绝", "rk-fail");
        assertThat(available(1L)).as("渠道失败释放预留，点数回到可用").isEqualTo(100);
        assertThat(jdbc.queryForObject(
                "SELECT point_reversal_state FROM refund_order WHERE id = ?", String.class, refundId))
                .isEqualTo("RELEASED");
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    // ========== 9. T13-22：channel + merchant_id + openid 均由 Adapter/会话上下文提供（去硬编码验证） ==========

    /**
     * T13-22 合同：Inbox 与 payment_transaction 的 channel/merchant_id 均取自 paymentPort，
     * 不再硬编码 'STUB'/'stub-merchant'（分派表 §8 红线 2）；为后续 stub → real 切换提供零业务改动的契约基线。
     */
    @Test
    void channelWrittenToTransactionFact() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-chan", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-chan\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-chan\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);

        assertThat(jdbc.queryForObject(
                "SELECT channel FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-chan"))
                .as("Inbox.channel 取自 paymentPort.channel()").isEqualTo("STUB");
        assertThat(jdbc.queryForObject(
                "SELECT channel FROM payment_transaction WHERE order_no = ?",
                String.class, order.orderNo()))
                .as("payment_transaction.channel 取自 paymentPort.channel()").isEqualTo("STUB");
        assertThat(jdbc.queryForObject(
                "SELECT merchant_id FROM payment_transaction WHERE order_no = ?",
                String.class, order.orderNo()))
                .as("payment_transaction.merchant_id 取自 paymentPort.merchantId()").isEqualTo("stub-merchant");
    }

    /**
     * T13-22 合同：openid 从 Controller 会话上下文透传到 Adapter，供真实 JSAPI 支付使用。
     * Stub 不消费 openid（无真实微信端点），但记录到 openidByOrder 供本断言验证透传链路完整。
     *
     * <p>分派表 §2 提及的 {@code openidMismatchAppIdRejected} 需要真实渠道验证（微信 API 返回
     * {@code OPENID_MISMATCH} 错误），归 T13-23 {@code WechatPaymentAdapter} + {@code FakeHttpServer} 覆盖。
     */
    @Test
    void openidPassedToChannelAdapter() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-openid", "test-openid-xyz");
        assertThat(stubChannel.openidOf(order.orderNo()))
                .as("openid 已从 createOrder 透传到 paymentPort.createPrepay")
                .isEqualTo("test-openid-xyz");
    }

    // ========== 10. T13-26：Inbox 幂等到账与补偿 ==========

    /**
     * T13-26 合同：主动查单兜底（reconcile）不写 payment_notification_inbox。
     * 分派表 §8 红线 7：“禁止查单兜底路径伪造签名通知写 inbox”。
     * reconcile 直接调 processPaymentFact + fulfillOrder，绕过 handleNotification，
     * 因此不会产生 inbox 记录（eventId = "reconcile-" + orderNo 只写入 payment_transaction）。
     */
    @Test
    void reconcileDoesNotWriteInbox() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-no-inbox", "test-openid-1");
        // 模拟通知丢失：直接推进支付事实（不经 handleNotification）
        payment.processPaymentFact(order.orderNo(), "evt-lost", "txn-lost", 1000, Instant.now());
        int inboxBefore = count("payment_notification_inbox");

        // 主动查单补偿到账
        String outcome = payment.reconcile(order.orderNo());
        assertThat(outcome).isIn("RECOVERED", "ALREADY_RECONCILED");
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState())
                .isEqualTo("CREDITED");

        // 核心断言：reconcile 不产生新的 inbox 记录
        assertThat(count("payment_notification_inbox"))
                .as("查单兜底禁止伪造签名通知写 inbox（分派表 §8 红线 7）")
                .isEqualTo(inboxBefore);
    }

    /**
     * T13-26 合同：Phase 1（INSERT inbox）提交后 Phase 2（到账）失败，
     * inbox 记录仍持久化（process_status='FAILED'），recoverPendingInbox 可重放。
     * 验证“可靠接收”语义：即使到账处理崩溃，通知不丢失。
     */
    @Test
    void inboxPersistedBeforeProcessingFailureThenRecovered() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-phase", "test-openid-1");
        byte[] body = ("{\"eventId\":\"evt-phase\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-phase\",\"amountCents\":1000,\"paidAtEpochSecond\":"
                + Instant.now().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8);

        // 正常处理：Phase 1 + Phase 2 都成功
        assertThat(payment.handleNotification(Map.of(), body)).isEqualTo("PROCESSED");
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState())
                .isEqualTo("CREDITED");

        // 验证 inbox 记录存在且 process_status='PROCESSED'
        assertThat(jdbc.queryForObject(
                "SELECT process_status FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-phase"))
                .isEqualTo("PROCESSED");

        // 模拟 Phase 2 失败后补偿：手动把 inbox 改为 FAILED，然后 recoverPendingInbox 重放
        jdbc.update("UPDATE payment_notification_inbox SET process_status = 'FAILED', "
                + "last_error = 'simulated crash' WHERE event_id = ?", "evt-phase");
        // 把订单到账状态回退（模拟 Phase 2 未完成）
        jdbc.update("UPDATE recharge_order SET fulfillment_state = 'NOT_READY' WHERE id = ?", order.orderId());
        jdbc.execute("DELETE FROM recharge_credit WHERE order_id = " + order.orderId());

        int recovered = payment.recoverPendingInbox();
        assertThat(recovered).as("recoverPendingInbox 应重放 FAILED 的 inbox").isGreaterThanOrEqualTo(1);
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState())
                .as("补偿后到账完成").isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject(
                "SELECT process_status FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-phase"))
                .as("补偿后 inbox 状态更新为 PROCESSED").isEqualTo("PROCESSED");
    }

    // ========== 11. T13-27：支付异常与账务事实校验 ==========

    /**
     * T13-27 合同：金额差 1 分拒绝 + 异常审计落表。
     * 分派表 §3.2 ①“金额一致性（通知金额 vs 订单快照）” + ②“校验失败必须落审计”。
     */
    @Test
    void amountMismatchRecordsAnomalyAudit() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-audit-amt", "test-openid-1");
        // 通知金额 999（差 1 分）
        byte[] body = ("{\"eventId\":\"evt-audit-amt\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-audit\",\"amountCents\":999,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> payment.handleNotification(Map.of(), body))
                .isInstanceOf(ServiceException.class);

        // 核心断言：异常审计已落表
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'AMOUNT_MISMATCH'",
                Integer.class, order.orderNo()))
                .as("金额不符必须落 payment_anomaly_audit（分派表 §3.2 ②）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT severity FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'AMOUNT_MISMATCH'",
                String.class, order.orderNo()))
                .isEqualTo("REJECT");
        // 支付事实未推进
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().paymentState()).isEqualTo("PENDING");
    }

    /**
     * T13-27 合同：transactionId 缺失拒绝 + 异常审计落表。
     * 分派表 §3.2 ①“transactionId 非空”。
     */
    @Test
    void transactionIdMissingRecordsAnomalyAudit() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-audit-txn", "test-openid-1");
        // transactionId 为空字符串
        byte[] body = ("{\"eventId\":\"evt-audit-txn\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> payment.handleNotification(Map.of(), body))
                .isInstanceOf(ServiceException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'TRANSACTION_ID_MISSING'",
                Integer.class, order.orderNo()))
                .as("transactionId 缺失必须落审计").isEqualTo(1);
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().paymentState()).isEqualTo("PENDING");
    }

    /**
     * T13-27 合同：缺实付金额时回退声明值 + AUDIT 标记（不拒绝）。
     * 分派表 §3.2 ④“缺实付金额时回退声明值 + 审计标记”。
     * 通过 reconcile 路径验证：stubChannel 的 queryOrder 默认返回 amountCents=null
     * （amountScript 未注入即命中该分支，且 ConcurrentHashMap 禁止 put null 值）。
     */
    @Test
    void channelAmountFallbackRecordsAudit() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-audit-fallback", "test-openid-1");
        // 配置 stub 查单返回 SUCCEEDED；transactionId 由 stub 派生为 "txn-"+orderNo（非空），
        // 而 amountScript 故意不注入 → queryOrder 的 amountCents=null（模拟渠道未返回实付金额）
        stubChannel.scriptQuery(order.orderNo(), "SUCCEEDED");

        String outcome = payment.reconcile(order.orderNo());
        assertThat(outcome).isIn("RECOVERED", "ALREADY_RECONCILED");
        // 到账成功（回退声明值 1000）
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().fulfillmentState()).isEqualTo("CREDITED");
        assertThat(available(1L)).isEqualTo(120);

        // 核心断言：AUDIT 标记已落表（不是 REJECT）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'CHANNEL_AMOUNT_FALLBACK'",
                Integer.class, order.orderNo()))
                .as("缺实付金额必须落 AUDIT 审计（分派表 §3.2 ④）").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT severity FROM payment_anomaly_audit WHERE order_no = ? AND anomaly_type = 'CHANNEL_AMOUNT_FALLBACK'",
                String.class, order.orderNo()))
                .as("回退是软告警，不是硬拒绍").isEqualTo("AUDIT");
    }

    // ========== 12. T13-28：退款结果通知入站 ==========

    /** 构造 Stub 支付通知体 */
    private byte[] payBody(String eventId, String orderNo, long amountCents) {
        return ("{\"eventId\":\"" + eventId + "\",\"orderNo\":\"" + orderNo
                + "\",\"transactionId\":\"txn-" + eventId + "\",\"amountCents\":" + amountCents
                + ",\"paidAtEpochSecond\":1}").getBytes(StandardCharsets.UTF_8);
    }

    /** 构造 Stub 退款通知体（T13-28 约定 JSON） */
    private byte[] refundBody(String eventId, String orderNo, String channelRefundId,
                              String state, long refundAmountCents) {
        return ("{\"eventId\":\"" + eventId + "\",\"orderNo\":\"" + orderNo
                + "\",\"channelRefundId\":\"" + channelRefundId + "\",\"state\":\"" + state
                + "\",\"refundAmountCents\":" + refundAmountCents
                + ",\"refundedAtEpochSecond\":2}").getBytes(StandardCharsets.UTF_8);
    }

    /** 已支付订单 + 渠道退款返回 PROCESSING（退款单落 UNKNOWN、预留保持），返回 [orderId, orderNo, refundId] */
    private Object[] seedPaidOrderWithProcessingRefund(String planKey, String requestKey) {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, planKey, "test-openid-1");
        payment.handleNotification(Map.of(), payBody("evt-" + planKey, order.orderNo(), 1000));
        assertThat(available(1L)).isEqualTo(120);
        stubChannel.scriptRefund(order.orderNo(), "PROCESSING");
        Long refundId = payment.requestRefund(order.orderId(), "admin-1", "误充", requestKey);
        return new Object[]{order.orderId(), order.orderNo(), refundId};
    }

    private String refundColumn(long refundId, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM refund_order WHERE id = ?",
                String.class, refundId);
    }

    /**
     * T13-28 合同：渠道退款 PROCESSING 后的 SUCCEEDED 通知驱动冲正。
     * 覆盖“请求未终态 → 回调终态”的真实微信时序。
     */
    @Test
    void refundNotificationSucceededReversesAfterProcessing() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rn-ok", "rk-rn-ok");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        // PROCESSING 阶段：退款单落 UNKNOWN，点数全额预留（未冲正也未释放）
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("UNKNOWN");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("RESERVED");
        assertThat(available(1L)).isZero();
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isEqualTo(120);

        String outcome = payment.handleRefundNotification(Map.of(),
                refundBody("evt-rn-ok", orderNo, "refund-" + refundId, "SUCCEEDED", 1000));
        assertThat(outcome).isEqualTo("REVERSED");

        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("REVERSED");
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("SUCCEEDED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
        assertThat(available(1L)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'RECHARGE_BASE_REVERSAL'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT process_status FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-rn-ok")).isEqualTo("PROCESSED");
    }

    /**
     * T13-28 合同（分派表 §8 红线 5）：PROCESSING 通知既不冲正也不释放，
     * 仅落 channel_state，终态交由 RefundRecoveryJob（T13-29）查单收口。
     */
    @Test
    void refundNotificationProcessingKeepsReservationUntouched() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rn-proc", "rk-rn-proc");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        String outcome = payment.handleRefundNotification(Map.of(),
                refundBody("evt-rn-proc", orderNo, "refund-" + refundId, "PROCESSING", 1000));
        assertThat(outcome).isEqualTo("PENDING");

        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("PROCESSING");
        assertThat(refundColumn(refundId, "point_reversal_state"))
                .as("红线 5：非终态不得冲正也不得释放").isEqualTo("RESERVED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isEqualTo(120);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",
                Integer.class)).isZero();
    }

    /** T13-28 合同：FAILED 通知释放预留，点数回到可用且不写冲正流水。 */
    @Test
    void refundNotificationFailedReleasesReservation() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rn-fail", "rk-rn-fail");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        String outcome = payment.handleRefundNotification(Map.of(),
                refundBody("evt-rn-fail", orderNo, "refund-" + refundId, "FAILED", 1000));
        assertThat(outcome).isEqualTo("RELEASED");

        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("FAILED");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("RELEASED");
        assertThat(available(1L)).as("释放后点数回到可用").isEqualTo(120);
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",
                Integer.class)).as("退款失败不得写冲正流水").isZero();
    }

    /** T13-28 合同：同 eventId 退款通知重复到达只推进一次（Inbox UK 幂等）。 */
    @Test
    void refundNotificationDuplicateIsIdempotent() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rn-dup", "rk-rn-dup");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];
        byte[] body = refundBody("evt-rn-dup", orderNo, "refund-" + refundId, "SUCCEEDED", 1000);

        assertThat(payment.handleRefundNotification(Map.of(), body)).isEqualTo("REVERSED");
        assertThat(payment.handleRefundNotification(Map.of(), body)).isEqualTo("DUPLICATE");

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_notification_inbox WHERE event_id = 'evt-rn-dup'",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'RECHARGE_BASE_REVERSAL'",
                Integer.class)).as("重复通知不得重复冲正").isEqualTo(1);
    }

    /**
     * T13-28 合同：退款金额与退款单不符（P0 仅整单全额）→ 拒绍 + 落审计，退款单不推进。
     * 分派表 §3.1 T13-28「退款金额与原订单一致性校验」。
     */
    @Test
    void refundNotificationAmountMismatchRecordsAuditAndRejects() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rn-amt", "rk-rn-amt");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        assertThatThrownBy(() -> payment.handleRefundNotification(Map.of(),
                refundBody("evt-rn-amt", orderNo, "refund-" + refundId, "SUCCEEDED", 999)))
                .isInstanceOf(ServiceException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_AMOUNT_MISMATCH'",
                Integer.class)).as("退款金额不符必须落审计").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT severity FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_AMOUNT_MISMATCH'",
                String.class)).isEqualTo("REJECT");
        assertThat(refundColumn(refundId, "point_reversal_state"))
                .as("拒绍后不得冲正").isEqualTo("RESERVED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isEqualTo(120);
        assertThat(jdbc.queryForObject(
                "SELECT process_status FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-rn-amt")).isEqualTo("FAILED");
    }

    /** T13-28 合同：退款通知指向未知退款单 → 拒绍 + 落审计（禁止静默吞掉）。 */
    @Test
    void refundNotificationUnknownRefundIdRecordsAudit() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-rn-unknown", "test-openid-1");
        payment.handleNotification(Map.of(), payBody("evt-rn-unknown-pay", order.orderNo(), 1000));

        // 未发起过退款，通知却声称退款成功
        assertThatThrownBy(() -> payment.handleRefundNotification(Map.of(),
                refundBody("evt-rn-unknown", order.orderNo(), "refund-999999", "SUCCEEDED", 1000)))
                .isInstanceOf(ServiceException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_ORDER_NOT_FOUND'",
                Integer.class)).isEqualTo(1);
        assertThat(available(1L)).as("未知退款单不得影响已到账点数").isEqualTo(120);
    }

    /**
     * 回归固化：同 orderNo 不同 eventId 的重发支付通知，inbox 必须记 PROCESSED。
     *
     * <p>{@code ck_payment_inbox_status} 只允许 RECEIVED/PROCESSED/FAILED；旧代码写 'REJECTED'
     * 会抛约束违反 → 500 → 渠道按退避无限重试，且 retry_count 永远停在 RECEIVED 无法被补偿扫描区分。
     */
    @Test
    void replayedPaymentNotificationWithNewEventIdMarksInboxProcessed() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-replay", "test-openid-1");
        assertThat(payment.handleNotification(Map.of(),
                payBody("evt-replay-1", order.orderNo(), 1000))).isEqualTo("PROCESSED");
        assertThat(available(1L)).isEqualTo(120);

        // 微信用不同通知 id 重发同一笔支付：Phase 1 INSERT 成功（event_id 不同），
        // Phase 2 processPaymentFact 因订单已 SUCCEEDED 返回 false
        String outcome = payment.handleNotification(Map.of(),
                payBody("evt-replay-2", order.orderNo(), 1000));
        assertThat(outcome).as("无状态迁移时对调用方返回 REJECTED").isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT process_status FROM payment_notification_inbox WHERE event_id = ?",
                String.class, "evt-replay-2"))
                .as("inbox 必须写合法枚举值 PROCESSED，不得写 REJECTED").isEqualTo("PROCESSED");
        assertThat(available(1L)).as("重发不得重复到账").isEqualTo(120);
    }

    // ========== 12. T13-29：退款未知状态与查单恢复 ==========

    /**
     * T13-29 合同（分派表 §3.2 ②③）：渠道退款 PROCESSING 落 UNKNOWN 后，定时查单转终态
     * SUCCEEDED → 冲正。覆盖“请求未终态 → 查单收口”的真实微信时序。
     */
    @Test
    void reconcileRefundsDrivesProcessingRefundToReversed() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rc-ok", "rk-rc-ok");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];
        // PROCESSING 阶段：退款单落 UNKNOWN、点数全额预留（未冲正也未释放）
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("UNKNOWN");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("RESERVED");
        assertThat(available(1L)).isZero();

        // 渠道查单转终态 SUCCEEDED
        stubChannel.scriptRefund(orderNo, "SUCCEEDED");
        int resolved = payment.reconcileRefunds();

        assertThat(resolved).as("查单终态应收口 1 笔").isEqualTo(1);
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("SUCCEEDED");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("REVERSED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'RECHARGE_BASE_REVERSAL'",
                Integer.class)).isEqualTo(1);
    }

    /**
     * T13-29 合同（分派表 §8 红线 5）：连续多次查单仍 PROCESSING，既不冲正也不释放，
     * 保持预留直到渠道终态。固化“PROCESSING/UNKNOWN 不得视为成功或失败”的硬约束。
     */
    @Test
    void reconcileRefundsKeepsReservationWhileStillProcessing() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rc-proc", "rk-rc-proc");
        long refundId = (Long) seed[2];

        for (int i = 0; i < 3; i++) {
            assertThat(payment.reconcileRefunds()).as("第 %d 轮仍 PROCESSING 不收口", i + 1).isZero();
        }

        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("UNKNOWN");
        assertThat(refundColumn(refundId, "point_reversal_state"))
                .as("红线 5：非终态不得冲正也不得释放").isEqualTo("RESERVED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isEqualTo(120);
        assertThat(available(1L)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",
                Integer.class)).isZero();
    }

    /** T13-29 合同（分派表 §3.2 ③）：查单终态 FAILED → 释放预留，点数回到可用且不写冲正流水。 */
    @Test
    void reconcileRefundsReleasesReservationWhenChannelFailed() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rc-fail", "rk-rc-fail");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        stubChannel.scriptRefund(orderNo, "FAILED");
        int resolved = payment.reconcileRefunds();

        assertThat(resolved).isEqualTo(1);
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("FAILED");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("RELEASED");
        assertThat(available(1L)).as("释放后点数回到可用").isEqualTo(120);
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",
                Integer.class)).as("退款失败不得写冲正流水").isZero();
    }

    /**
     * T13-29 合同（分派表 §3.2 ④）：重启补偿 recoverPendingRefunds 重驱悬挂退款单查单收口。
     * 模拟进程重启后渠道已终态 SUCCEEDED，补偿应完成冲正。
     */
    @Test
    void recoverPendingRefundsResolvesOnRestart() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rp-ok", "rk-rp-ok");
        String orderNo = (String) seed[1];
        long refundId = (Long) seed[2];

        stubChannel.scriptRefund(orderNo, "SUCCEEDED");
        int resolved = payment.recoverPendingRefunds();

        assertThat(resolved).as("重启补偿应收口悬挂退款").isEqualTo(1);
        assertThat(refundColumn(refundId, "channel_state")).isEqualTo("SUCCEEDED");
        assertThat(refundColumn(refundId, "point_reversal_state")).isEqualTo("REVERSED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isZero();
    }

    /**
     * T13-29 合同（卡单可观测）：超过宽限期仍卡在非终态的退款，重启补偿落一条 REFUND_STUCK 审计（AUDIT 级），
     * 且保持预留不冲正不释放（红线 5）；重复补偿去重，不重复告警。
     */
    @Test
    void recoverPendingRefundsAuditsStuckRefund() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-rp-stuck", "rk-rp-stuck");
        long refundId = (Long) seed[2];
        // 仍 PROCESSING（查单不终态），且已超过 30min 宽限期
        jdbc.update("UPDATE refund_order SET update_time = now() - interval '60 minutes' WHERE id = ?", refundId);

        int resolved = payment.recoverPendingRefunds();

        assertThat(resolved).as("未终态不收口").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_STUCK'",
                Integer.class)).as("卡单必须落审计").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT severity FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_STUCK'",
                String.class)).isEqualTo("AUDIT");
        assertThat(refundColumn(refundId, "point_reversal_state"))
                .as("红线 5：卡单审计不改变状态，仍保持预留").isEqualTo("RESERVED");
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints()).isEqualTo(120);

        // 去重：再次补偿不重复落审计
        payment.recoverPendingRefunds();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type = 'REFUND_STUCK'",
                Integer.class)).as("卡单审计去重").isEqualTo(1);
    }

    /**
     * T13-29 合同（分派表 §3.2 ⑤）：渠道请求超时后受理方重试不得重复发起退款。
     * 同 requestKey 幂等返回既有退款单；不同 requestKey 被 priorRefunds 守卫拒绝，防渠道重复打款。
     */
    @Test
    void refundRequestIsIdempotentAndBlocksDoubleIssue() {
        Object[] seed = seedPaidOrderWithProcessingRefund("key-di", "rk-di");
        long orderId = (Long) seed[0];
        long refundId = (Long) seed[2];

        // 同 requestKey 重放（超时后受理方重试）：返回既有退款单，不新增退款单
        Long replay = payment.requestRefund(orderId, "admin-1", "误充", "rk-di");
        assertThat(replay).as("幂等：同 requestKey 返回既有退款单").isEqualTo(refundId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refund_order WHERE order_id = ?", Integer.class, orderId))
                .as("不得重复发起退款单").isEqualTo(1);

        // 不同 requestKey：被 priorRefunds 守卫拒绝（该订单已有非 FAILED 退款单）
        assertThatThrownBy(() -> payment.requestRefund(orderId, "admin-1", "误充", "rk-di-other"))
                .isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM refund_order WHERE order_id = ?", Integer.class, orderId))
                .isEqualTo(1);
        assertThat(points.findAccount(1L).orElseThrow().reservedPoints())
                .as("预留不得翻倍").isEqualTo(120);
    }

}
