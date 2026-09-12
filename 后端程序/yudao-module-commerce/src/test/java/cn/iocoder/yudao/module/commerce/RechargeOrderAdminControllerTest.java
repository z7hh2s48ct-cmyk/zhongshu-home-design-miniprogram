package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.commerce.controller.admin.RechargeOrderAdminController;
import cn.iocoder.yudao.module.commerce.payment.PaymentFactValidator;
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
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13-30 前半 管理端合同测试（真实 PostgreSQL）。
 *
 * <p>覆盖分派表 §3.3 泳道 C 的后端前置：
 * <ul>
 *   <li>①a 订单详情下发真实渠道资金归属字段（channel/merchantId/channelTransactionId/channelPaidAt），
 *       供管理后台详情弹窗展示、财务核对；</li>
 *   <li>③ 渠道支付流水分页（GET /payment-transactions，复用 payment_transaction），支持按 channel/orderNo 过滤。</li>
 * </ul>
 *
 * <p>基座与 {@link PaymentP8AContractTest} 一致：Testcontainers 起真实 PG + Flyway 迁移 + 手动装配
 * RechargePaymentService，再用 ReflectionTestUtils 注入控制器的 JdbcTemplate（生产由 @Resource 注入）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RechargeOrderAdminControllerTest {

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
    private RechargeOrderAdminController controller;

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
        PaymentFactValidator factValidator = new PaymentFactValidator(dataSource, stubChannel);
        payment = new RechargePaymentService(dataSource, txManager, stubChannel,
                new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(points),
                points, new JdbcReliableEventPort(dataSource), factValidator);

        controller = new RechargeOrderAdminController(payment);
        ReflectionTestUtils.setField(controller, "jdbc", jdbc);
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

    /** 建单并驱动一次成功支付通知，写入 payment_transaction（Stub channel=STUB / merchant=stub-merchant）。 */
    private RechargePaymentService.OrderSnapshot seedPaidOrder(long userId, String key, String txnId, long amountCents) {
        long planId = seedPlan(amountCents, 100, 20);
        var order = payment.createOrder(userId, planId, key, "openid-" + key);
        byte[] body = ("{\"eventId\":\"evt-" + key + "\",\"orderNo\":\"" + order.orderNo()
                + "\",\"transactionId\":\"" + txnId + "\",\"amountCents\":" + amountCents
                + ",\"paidAtEpochSecond\":" + Instant.now().getEpochSecond() + "}").getBytes(StandardCharsets.UTF_8);
        assertThat(payment.handleNotification(Map.of(), body)).isEqualTo("PROCESSED");
        return payment.getOrderById(order.orderId()).orElseThrow();
    }

    // ========== ①a 订单详情下发渠道资金归属字段 ==========

    @Test
    void orderDetailExposesChannelFields() {
        var order = seedPaidOrder(1L, "t30-detail", "txn-t30-detail", 1000);

        CommonResult<Map<String, Object>> resp = controller.getOrder(String.valueOf(order.orderId()));
        Map<String, Object> data = resp.getData();

        assertThat(data).isNotNull();
        assertThat(data.get("orderNo")).isEqualTo(order.orderNo());
        assertThat(data.get("paymentState")).isEqualTo("SUCCEEDED");
        // T13-30 前半 ①a：详情弹窗展示真实渠道字段，供财务核对资金归属
        assertThat(data.get("channel")).isEqualTo("STUB");
        assertThat(data.get("merchantId")).isEqualTo("stub-merchant");
        assertThat(data.get("channelTransactionId")).isEqualTo("txn-t30-detail");
        assertThat(data.get("channelPaidAt")).isNotNull();
    }

    @Test
    void orderDetailOmitsChannelFieldsWhenUnpaid() {
        // 未支付订单无 payment_transaction 流水：不得下发 channel 等字段（避免误导财务）
        long planId = seedPlan(1000, 100, 20);
        var order = payment.createOrder(1L, planId, "t30-unpaid", "openid-unpaid");

        Map<String, Object> data = controller.getOrder(String.valueOf(order.orderId())).getData();

        assertThat(data).isNotNull();
        assertThat(data.get("paymentState")).isEqualTo("PENDING");
        assertThat(data).doesNotContainKeys("channel", "merchantId", "channelTransactionId", "channelPaidAt");
    }

    // ========== ③ 渠道支付流水分页 ==========

    @Test
    void paymentTransactionPageReturnsChannelFacts() {
        var order = seedPaidOrder(1L, "t30-page", "txn-t30-page", 2000);

        CommonResult<PageResult<Map<String, Object>>> resp =
                controller.getPaymentTransactionPage(null, null, 1, 20);
        PageResult<Map<String, Object>> page = resp.getData();

        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getList()).hasSize(1);
        Map<String, Object> row = page.getList().get(0);
        assertThat(row.get("channel")).isEqualTo("STUB");
        assertThat(row.get("merchantId")).isEqualTo("stub-merchant");
        assertThat(row.get("channelTransactionId")).isEqualTo("txn-t30-page");
        assertThat(row.get("orderNo")).isEqualTo(order.orderNo());
        assertThat(row.get("amountCents")).isEqualTo(2000L);
        assertThat((String) row.get("paidAt")).isNotBlank();
        assertThat((String) row.get("createdAt")).isNotBlank();
    }

    @Test
    void paymentTransactionPageFiltersByChannelAndOrderNo() {
        var o1 = seedPaidOrder(1L, "t30-f1", "txn-t30-f1", 1000);
        seedPaidOrder(2L, "t30-f2", "txn-t30-f2", 1000);

        // 无过滤：两笔
        assertThat(controller.getPaymentTransactionPage(null, null, 1, 20).getData().getTotal()).isEqualTo(2L);
        // 按 orderNo 过滤：仅一笔，且命中正确订单
        PageResult<Map<String, Object>> byOrder =
                controller.getPaymentTransactionPage(null, o1.orderNo(), 1, 20).getData();
        assertThat(byOrder.getTotal()).isEqualTo(1L);
        assertThat(byOrder.getList().get(0).get("orderNo")).isEqualTo(o1.orderNo());
        // 按 channel 过滤：STUB 命中两笔，未知渠道零笔
        assertThat(controller.getPaymentTransactionPage("STUB", null, 1, 20).getData().getTotal()).isEqualTo(2L);
        assertThat(controller.getPaymentTransactionPage("WECHAT", null, 1, 20).getData().getTotal()).isEqualTo(0L);
    }
}
