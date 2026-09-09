package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetController;
import cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real PG storage/ownership plus controller projections; not a live HTTP or mini-program E2E test. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetProjectInputsT10ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private final AiJobPort jobs = mock(AiJobPort.class);
    private final IdentitySessionPort identities = token -> switch (token == null ? "" : token) {
        case "owner" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "test", false));
        case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "test", true));
        default -> Optional.empty();
    };

    @BeforeAll
    void setUp() {
        var ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        jdbc = new JdbcTemplate(ds);
        projects = new DesignProjectService(ds, new DataSourceTransactionManager(ds), jobs, null, null, null);
    }

    @BeforeEach
    void cleanIsolatedContainer() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_result_version, design_selection");
        reset(jobs);
    }

    @Test
    void creationPersistsCanonicalInputsAndLaterElevationOnlySnapshotDoesNotLoseThem() {
        long projectId = projects.createProject(1, "SELF_UPLOAD", null, null,
                Map.of("prompt", "原始要求", "budgetInputs", Map.of("footprintArea", "120.0000", "floorCount", 2, "buildingArea", "251.2")));
        var original = jdbc.queryForMap("SELECT id, inputs::text FROM design_requirement_snapshot WHERE project_id = ?", projectId);
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs) VALUES (100, ?, 2, '{\"styleCode\":\"MODERN\"}')", projectId);
        var imported = projects.getBudgetInputSnapshot(1, projectId, null);
        var resolved = BudgetInputs.resolve(imported.inputs(), Map.of());
        assertThat(resolved.values()).containsEntry("footprintArea", "120").containsEntry("buildingArea", "251.2");
        assertThat(resolved.missingFields()).contains("regionCode", "roofArea");
        assertThat(imported.requirementSnapshotIds()).containsExactly(String.valueOf(original.get("id")));
        assertThat(jdbc.queryForObject("SELECT inputs::text FROM design_requirement_snapshot WHERE id = ?", String.class, original.get("id")))
                .contains("原始要求", "budgetInputs").doesNotContain("120.0000");
    }

    @Test
    void resultReadsImportOnlyParametersAvailableAtThatVersionAndPreferItsActualArea() {
        long projectId = projects.createProject(1, "SELF_UPLOAD", null, null,
                Map.of("budgetInputs", Map.of("footprintArea", "120", "floorCount", 2, "roofArea", "112")));
        jdbc.update("UPDATE design_requirement_snapshot SET create_time = '2026-01-01T00:00:00Z' WHERE project_id = ?", projectId);
        insertVersion(9007199254740993L, projectId, "{\"buildingArea\":251.2}", "2026-01-05T00:00:00Z");
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                + "VALUES (100, ?, 2, '{\"budgetInputs\":{\"footprintArea\":\"130\",\"roofArea\":null}}', '2026-01-10T00:00:00Z')", projectId);
        var historical = BudgetInputs.resolve(projects.getBudgetInputSnapshot(1, projectId, 9007199254740993L).inputs(), Map.of());
        assertThat(historical.values()).containsEntry("footprintArea", "120").containsEntry("buildingArea", "251.2").containsEntry("roofArea", "112");
        var latest = BudgetInputs.resolve(projects.getBudgetInputSnapshot(1, projectId, null).inputs(), Map.of());
        assertThat(latest.values()).containsEntry("footprintArea", "130").containsEntry("buildingArea", "260").doesNotContainKey("roofArea");
        assertThat(historical.values()).containsEntry("footprintArea", "120");
    }

    @Test
    void foreignMissingAndDeletedProjectsOrVersionsAreRejected() {
        long own = projects.createProject(1, "SELF_UPLOAD", null, null, null);
        long other = projects.createProject(2, "SELF_UPLOAD", null, null, null);
        insertVersion(100, other, "{}", "2026-01-01T00:00:00Z");
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(2, own, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(1, 999, null)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(1, own, 100L)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(1, own, 999L)).isInstanceOf(ServiceException.class);
        insertVersion(101, own, "{}", "2026-01-01T00:00:00Z");
        jdbc.update("UPDATE design_result_version SET deleted = TRUE WHERE id = 101");
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(1, own, 101L)).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE design_project SET deleted = TRUE WHERE id = ?", own);
        assertThatThrownBy(() -> projects.getBudgetInputSnapshot(1, own, null)).isInstanceOf(ServiceException.class);
    }

    @Test
    void invalidNestedInputsDoNotCreateProjectOrChargeElevationJob() {
        assertThatThrownBy(() -> projects.createProject(1, "SELF_UPLOAD", null, null,
                Map.of("budgetInputs", Map.of("footprintArea", "-1")))).isInstanceOf(ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_project", Integer.class)).isZero();
        long projectId = projects.createProject(1, "SELF_UPLOAD", null, null, Map.of("floor", "两层"));
        jdbc.update("INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) VALUES (500, ?, 'FLAT', 100, 1)", projectId);
        assertThatThrownBy(() -> projects.createElevationJob(1, projectId, 1, "invalid-budget-inputs",
                Map.of("budgetInputs", Map.of("quantities", Map.of("DOOR_HOUSEHOLDS", "1.5")))))
                .isInstanceOf(ServiceException.class);
        verifyNoInteractions(jobs);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_requirement_snapshot WHERE project_id = ?", Integer.class, projectId)).isEqualTo(1);
    }

    @Test
    void budgetControllerUsesUnrestrictedSessionAndExplicitPublicProjection() {
        long projectId = projects.createProject(1, "SELF_UPLOAD", null, null, Map.of("prompt", "private note",
                "budgetInputs", Map.of("footprintArea", "120", "floorCount", 2, "quantities", Map.of("CULTURE_STONE_LENGTH", "44"))));
        var controller = new AppBudgetController();
        ReflectionTestUtils.setField(controller, "designProjectService", projects);
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        for (String token : new String[]{"Bearer restricted", "Bearer invalid", ""}) {
            assertThatThrownBy(() -> controller.getBudgetInputs(String.valueOf(projectId), null, token))
                    .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(
                            token.contains("restricted") ? IdentitySessionPort.ACCESS_GRANT_REQUIRED : 401));
        }
        var response = controller.getBudgetInputs(String.valueOf(projectId), null, "Bearer owner").getData();
        assertThat(response.projectId()).isEqualTo(String.valueOf(projectId));
        assertThat(response.importedValues()).containsEntry("buildingArea", "240");
        assertThat(response.sources()).containsEntry("buildingArea", "DERIVED");
        assertThat(response.missingFields()).contains("roofArea", "regionCode");
        assertThat(JsonUtils.toJsonString(response)).doesNotContain("quantities", "CULTURE_STONE_LENGTH", "private note", "prompt", "unitPriceCents");
        assertThatThrownBy(() -> controller.getBudgetInputs(String.valueOf(projectId), "9223372036854775808", "owner"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void existingResultVersionEndpointDoesNotLeakNewPrivateBudgetFields() {
        long projectId = projects.createProject(1, "SELF_UPLOAD", null, null, null);
        String original = "{\"styleCode\":\"MODERN\",\"budgetInputs\":{\"roofArea\":\"112\",\"quantities\":{\"DOOR_HOUSEHOLDS\":\"1\"}}}";
        insertVersion(9007199254740993L, projectId, original, "2026-01-01T00:00:00Z");
        var controller = new AppDesignProjectController();
        ReflectionTestUtils.setField(controller, "designProjectService", projects);
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        var version = controller.getResultVersions(String.valueOf(projectId), "owner").getData().getList().get(0);
        assertThat(version.getVersionId()).isEqualTo("9007199254740993");
        assertThat(version.getConfigSnapshot()).contains("MODERN", "roofArea").doesNotContain("quantities", "DOOR_HOUSEHOLDS");
        assertThat(jdbc.queryForObject("SELECT config_snapshot::text FROM design_result_version WHERE id = 9007199254740993", String.class))
                .contains("DOOR_HOUSEHOLDS"); // Projection must not rewrite the immutable internal snapshot.
    }

    private void insertVersion(long id, long projectId, String config, String time) {
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids, config_snapshot, create_time) "
                + "VALUES (?, ?, ?, 1, '[]', CAST(? AS jsonb), CAST(? AS timestamptz))", id, projectId, id, config, time);
    }
}
