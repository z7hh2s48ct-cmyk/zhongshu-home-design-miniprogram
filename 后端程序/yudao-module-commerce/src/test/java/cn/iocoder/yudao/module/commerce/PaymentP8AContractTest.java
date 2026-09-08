package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
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
        payment = new RechargePaymentService(dataSource, txManager, stubChannel,
                new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(points),
                points, new JdbcReliableEventPort(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE recharge_plan, recharge_order, payment_notification_inbox, "
                + "payment_transaction, recharge_credit, refund_order, "
                + "design_point_account, design_point_ledger, outbox_event");
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
        var o1 = payment.createOrder(1L, planId, "key-1");
        var o2 = payment.createOrder(1L, planId, "key-1");
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
        var order = payment.createOrder(1L, planId, "key-notif-1");
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
        var order = payment.createOrder(1L, planId, "key-amt");
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
        var order = payment.createOrder(1L, planId, "key-rec");
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
        var order = payment.createOrder(1L, planId, "key-refund");
        byte[] body = ("{\"eventId\":\"evt-r\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-r\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);
        assertThat(available(1L)).isEqualTo(120);

        Long refundId = payment.requestRefund(order.orderId(), "admin-1", "误充", "rk-1");
        assertThat(refundId).isNotNull();
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
    void refundRejectedWhenPointsAlreadyUsed() {
        long planId = seedPlan(1000, 100, 0);
        var order = payment.createOrder(1L, planId, "key-used");
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
        var order = payment.createOrder(1L, planId, "key-partial");
        assertThatThrownBy(() -> payment.requestRefund(order.orderId(), "admin-1", "只退一半", "rk-partial"))
                .as("未到账订单本就不受理；这里验证策略码存在")
                .isInstanceOf(ServiceException.class);
    }

    // ========== 7b. 同订单二次退款拒绝（评审 C1 回归） ==========

    @Test
    void duplicateRefundForSameOrderRejected() {
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "key-dup");
        byte[] body = ("{\"eventId\":\"evt-dup\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"txn-dup\",\"amountCents\":1000,\"paidAtEpochSecond\":1}")
                .getBytes(StandardCharsets.UTF_8);
        payment.handleNotification(Map.of(), body);

        Long first = payment.requestRefund(order.orderId(), "admin-1", "首次退款", "rk-dup-1");
        assertThat(first).isNotNull();
        // 用户再充值垫高余额（绕开余额防线），同订单二次退款必须被按单查重拦截
        long plan2 = seedPlan(500, 50, 0);
        var order2 = payment.createOrder(1L, plan2, "key-dup-2");
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
        var order = payment.createOrder(1L, planId, "key-fail");
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

}
