package cn.iocoder.yudao.server;

import cn.iocoder.yudao.module.identity.controller.admin.AccountAdminController;
import cn.iocoder.yudao.module.identity.controller.app.AppProfileController;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class AccountReadModelContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    static SimpleDriverDataSource ds;
    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/identity",
                "classpath:db/migration/design", "classpath:db/migration/commerce", "classpath:db/migration/ai-orchestration").load().migrate();
    }

    @Test
    void newlyRegisteredAccountWithoutPointsOrLedgerCanBeRead() {
        jdbc.update("INSERT INTO account (id, status) VALUES (2100000000000000001, 'ACTIVE')");
        var controller = new AccountAdminController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        var detail = controller.getAccount("2100000000000000001").getData();
        assertThat(detail.get("id")).isEqualTo("2100000000000000001");
        assertThat(detail.get("availablePoints")).isEqualTo(0L);
        assertThat((java.util.List<?>) detail.get("recentLedger")).isEmpty();
    }

    @Test
    void publishedCountExcludesApprovedDraftOfflineAndDeletedCases() {
        jdbc.update("INSERT INTO design_case (id, source_type, creator_user_id, publication_status, deleted) VALUES "
                + "(1,'AI',2,'PUBLISHED',FALSE),(2,'AI',2,'OFFLINE',FALSE),(3,'AI',2,'DRAFT',FALSE),(4,'AI',2,'PUBLISHED',TRUE)");
        for (long id = 1; id <= 4; id++) jdbc.update("INSERT INTO case_submission "
                + "(id, project_id, user_id, result_version_id, status, published_case_id) VALUES (?,10,2,20,'APPROVED',?)", id, id);
        jdbc.update("INSERT INTO case_submission (id, project_id, user_id, result_version_id, status) VALUES (5,10,2,20,'APPROVED')");
        var controller = new AppProfileController();
        ReflectionTestUtils.setField(controller, "dataSource", ds);
        Map<String, Integer> stats = ReflectionTestUtils.invokeMethod(controller, "buildStats", 2L);
        assertThat(stats).containsEntry("publishedCount", 1).containsEntry("projectCount", 0);
        jdbc.update("UPDATE design_case SET publication_status='OFFLINE' WHERE id=1");
        stats = ReflectionTestUtils.invokeMethod(controller, "buildStats", 2L);
        assertThat(stats).containsEntry("publishedCount", 0);
    }
}
