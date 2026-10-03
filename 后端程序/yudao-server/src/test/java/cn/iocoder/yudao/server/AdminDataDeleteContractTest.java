package cn.iocoder.yudao.server;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.service.SecurityFrameworkService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.server.controller.admin.AdminDataDeleteController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Testcontainers
class AdminDataDeleteContractTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;
    AdminDataDeleteController controller;

    @BeforeAll static void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/commerce")
                .load().migrate();
    }

    @BeforeEach void setUp() {
        jdbc.execute("TRUNCATE recharge_order, payment_transaction, audit_event");
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(501L).setUserType(2).setTenantId(1L),
                new MockHttpServletRequest());
        controller = new AdminDataDeleteController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        ReflectionTestUtils.setField(controller, "auditPort", mock(AuditPort.class));
    }

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void bothDeleteEndpointsRequireTheMatchingBusinessPermission() throws Exception {
        var single = AdminDataDeleteController.class.getMethod("deleteOne", String.class, String.class)
                .getAnnotation(PreAuthorize.class).value();
        var batch = AdminDataDeleteController.class.getMethod("deleteBatch", Map.class)
                .getAnnotation(PreAuthorize.class).value();
        for (String expression : new String[]{single, batch}) {
            assertThat(allowed(expression, "recharge-order", "design:export:manage")).isFalse();
            assertThat(allowed(expression, "recharge-order", "commerce:recharge-order:delete")).isFalse();
            assertThat(allowed(expression, "recharge-order", "design:export:manage",
                    "commerce:recharge-order:delete")).isTrue();
            assertThat(allowed(expression, "account", "design:export:manage",
                    "commerce:recharge-order:delete")).isFalse();
        }
    }

    private boolean allowed(String expression, String type, String... permissions) {
        SecurityFrameworkService ss = mock(SecurityFrameworkService.class);
        for (String permission : permissions) when(ss.hasPermission(permission)).thenReturn(true);
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setVariable("p0", expression.contains("['type']") ? Map.of("type", type) : type);
        context.setBeanResolver((ctx, name) -> switch (name) {
            case "ss" -> ss;
            case "adminDataDeleteController" -> controller;
            default -> throw new IllegalArgumentException(name);
        });
        return Boolean.TRUE.equals(new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class));
    }

    @Test void payableAndUnsettledOrdersStayVisible() {
        seedOrder(1L, "PENDING", "NOT_READY");
        seedOrder(2L, "UNKNOWN", "NOT_READY");
        seedOrder(3L, "SUCCEEDED", "PENDING");
        for (long id = 1; id <= 3; id++) {
            assertThat(controller.deleteOne("recharge-order", String.valueOf(id)).getData()).isFalse();
            assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=?", Boolean.class, id)).isFalse();
        }
    }

    @Test void onlyUnpaidTerminalOrderWithoutPaymentFactCanBeHidden() {
        seedOrder(4L, "CLOSED", "NOT_READY");
        seedOrder(5L, "FAILED", "NOT_READY");
        jdbc.update("INSERT INTO payment_transaction(id,channel,merchant_id,channel_transaction_id,order_no,amount_cents,paid_at) "
                + "VALUES (50,'STUB','merchant','txn-5','order-5',1000,now())");
        assertThat(controller.deleteOne("recharge-order", "4").getData()).isTrue();
        assertThat(controller.deleteOne("recharge-order", "5").getData()).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=4", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=5", Boolean.class)).isFalse();
    }

    @Test void batchDeleteSkipsPayableOrders() {
        seedOrder(6L, "PENDING", "NOT_READY");
        seedOrder(7L, "FAILED", "NOT_READY");
        assertThat(controller.deleteBatch(Map.of("type", "recharge-order", "ids", List.of("6", "7")))
                .getData()).containsEntry("deleted", 1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=6", Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT deleted FROM recharge_order WHERE id=7", Boolean.class)).isTrue();
    }

    private void seedOrder(long id, String paymentState, String fulfillmentState) {
        jdbc.update("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points,"
                        + "payment_state,fulfillment_state) VALUES (?, ?, 1, 1, '{}'::jsonb, 1000, 100, 0, ?, ?)",
                id, "order-" + id, paymentState, fulfillmentState);
    }
}
