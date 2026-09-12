package cn.iocoder.yudao.server;

import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.controller.admin.DashboardAdminController;
import cn.iocoder.yudao.module.design.controller.admin.ExportAuditAdminController;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
class OperationsReadModelContractTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;

    @BeforeAll static void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/identity",
                "classpath:db/migration/design", "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration").load().migrate();
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE export_job, one_time_download_ticket, ai_job, ai_job_settlement, design_point_ledger");
        login(501);
    }
    @AfterEach void clearLogin() { SecurityContextHolder.clearContext(); }
    private void login(long id) { SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(id).setUserType(2).setTenantId(1L), new MockHttpServletRequest()); }
    private ExportAuditAdminController exports(JdbcDeliveryPort delivery, ObjectStoragePort storage) {
        var controller = new ExportAuditAdminController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        ReflectionTestUtils.setField(controller, "deliveryPort", delivery);
        ReflectionTestUtils.setField(controller, "storage", storage);
        return controller;
    }
    private long job(JdbcDeliveryPort delivery, long owner, String type) {
        return delivery.createExportJob(ExportJobRequest.builder().jobType("POINT_LEDGER").requesterType(type).requesterUserId(owner).build());
    }

    @Test void exportsSurviveReloadAreOwnerScopedAndSingleUse() throws Exception {
        var delivery = new JdbcDeliveryPort(ds);
        var storage = mock(ObjectStoragePort.class);
        when(storage.getObject("file.csv")).thenAnswer(i -> new java.io.ByteArrayInputStream("id\n1\n".getBytes()));
        long own = job(delivery, 501, "ADMIN"), foreign = job(delivery, 502, "ADMIN");
        job(delivery, 501, "USER");
        delivery.completeExportJob(own, "file.csv", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest("id\n1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))), 3600);
        delivery.completeExportJob(foreign, "other.csv", "hash", 3600);
        var controller = exports(delivery, storage);
        assertThat(controller.getExportJobs(1, 20).getData().getTotal()).isEqualTo(1L);
        assertThat(exports(delivery, storage).getExportJobs(1, 20).getData().getList().get(0)).containsEntry("exportJobId", String.valueOf(own));
        assertThat(controller.getExportJob(String.valueOf(own)).getData()).doesNotContainKey("fileAssetId");
        assertThatThrownBy(() -> controller.getExportJob(String.valueOf(foreign))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.createDownloadTicket(String.valueOf(foreign))).isInstanceOf(AccessDeniedException.class);
        String ticket = (String) controller.createDownloadTicket(String.valueOf(own)).getData().get("ticket");
        login(502);
        assertThatThrownBy(() -> controller.downloadExportFile(String.valueOf(own), ticket)).isInstanceOf(AccessDeniedException.class);
        login(501);
        assertThat(controller.downloadExportFile(String.valueOf(own), ticket).getBody()).isEqualTo("id\n1\n".getBytes());
        assertThatThrownBy(() -> controller.downloadExportFile(String.valueOf(own), ticket)).isInstanceOf(AccessDeniedException.class);
        String retry = (String) controller.createDownloadTicket(String.valueOf(own)).getData().get("ticket");
        assertThat(controller.downloadExportFile(String.valueOf(own), retry).getBody()).isNotEmpty();
        when(storage.getObject("file.csv")).thenAnswer(i -> new java.io.ByteArrayInputStream("overwritten".getBytes()));
        String tampered = (String) controller.createDownloadTicket(String.valueOf(own)).getData().get("ticket");
        assertThatThrownBy(() -> controller.downloadExportFile(String.valueOf(own), tampered)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void expiredExportCannotIssueOrUseAnEarlierTicket() {
        var delivery = new JdbcDeliveryPort(ds);
        long id = job(delivery, 501, "ADMIN");
        delivery.completeExportJob(id, "file.csv", "hash", 3600);
        var storage = mock(ObjectStoragePort.class);
        var controller = exports(delivery, storage);
        String ticket = (String) controller.createDownloadTicket(String.valueOf(id)).getData().get("ticket");
        jdbc.update("UPDATE export_job SET expires_at=now()-interval '1 second' WHERE id=?", id);
        assertThat(controller.getExportJob(String.valueOf(id)).getData()).containsEntry("status", "EXPIRED");
        assertThatThrownBy(() -> controller.createDownloadTicket(String.valueOf(id))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.downloadExportFile(String.valueOf(id), ticket)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(storage);
    }

    @Test void dashboardCountsRealBusinessDaysAndExcludesRechargeReversals() {
        jdbc.update("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix) VALUES "
                + "(1,1,'FLAT','SUCCEEDED',1,'test'),(2,1,'FLAT','SUCCEEDED',1,'test'),(3,1,'FLAT','PARTIALLY_SUCCEEDED',2,'test'),(4,1,'FLAT','VALIDATING',1,'test')");
        for (long id = 1; id <= 3; id++) jdbc.update("INSERT INTO ai_job_settlement(id,job_id,settlement_version,requested_count,accepted_billable_count,unit_point_cost,original_debit,refunded_points,reason,create_time) "
                + "VALUES (?,?,1,1,1,10,10,0,'SETTLE', ((now() AT TIME ZONE 'Asia/Shanghai')::date + (? * interval '1 day') + interval '1 minute') AT TIME ZONE 'Asia/Shanghai')", id, id, id == 2 ? -1 : 0);
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after,idempotency_key) VALUES "
                + "(11,1,'FLAT_GENERATION_DEBIT',-10,90,0,'g'),(12,1,'RECHARGE_BASE_REVERSAL',-550,0,0,'r'),(13,1,'TASK_SETTLEMENT_REFUND',10,10,0,'t')");
        var controller = new DashboardAdminController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        var summary = controller.getSummary().getData();
        assertThat(summary).containsEntry("aiJobsSucceededToday", 1L).containsEntry("aiJobsRunning", 1L)
                .containsEntry("pointsConsumedToday", 10L).containsEntry("generationPointsRefundedToday", 10L);
        @SuppressWarnings("unchecked") var trend = (List<Map<String, Object>>) summary.get("aiTrend");
        assertThat(trend).hasSize(7);
        assertThat(trend.get(6)).containsEntry("count", 1L);
        assertThat(trend.get(5)).containsEntry("count", 1L);
    }
}
