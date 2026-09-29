import com.fasterxml.jackson.databind.ObjectMapper;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetQuarantineAdapter;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
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
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T15 合同测试（真实 PostgreSQL）：设计生成需求输入契约——
 * 白名单校验（未知键拒绝）、新键归一化落快照、参考案例元数据兜底且用户输入优先、
 * 旧形状（floor/family/prompt/note）与 budgetInputs 契约回归。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignRequirementInputsT15ContractTest {

    private static final long USER_A = 3301L;
    private static final long ADMIN = 9901L;
    private static final int ERR_DESIGN_INPUT = 1_071_000_005;
    private static final int ERR_BUDGET_INPUT = 1_071_000_100;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private CaseCatalogService catalog;
    private RightsGrantService rights;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeAll
    void setUp() throws Exception {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration", "classpath:db/migration/design")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        var points = new PointAccountService(dataSource, txManager);
        var priceRules = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        var pricingPort = new PricingPortAdapter(priceRules);
        var ledgerPort = new PointLedgerPortAdapter(points);
        var eventPort = new JdbcReliableEventPort(dataSource);
        var objectStorage = new LocalObjectStorageAdapter(
                Files.createTempDirectory("t15-assets").toString());
        QuarantineObjectPort quarantine = new AssetQuarantineAdapter(objectStorage, new AssetContentScanner());
        rights = new RightsGrantService(dataSource);
        catalog = new CaseCatalogService(dataSource, txManager);
        var orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        var settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_case, "
                + "design_case_version, design_case_asset, case_publication, asset_rights_grant, "
                + "asset CASCADE");
    }

    private Map<String, Object> snapshotOf(long projectId) throws Exception {
        List<String> rows = jdbc.queryForList(
                "SELECT inputs::text FROM design_requirement_snapshot WHERE project_id = ? AND deleted = FALSE",
                String.class, projectId);
        assertThat(rows).hasSize(1);
        return json.readValue(rows.get(0), Map.class);
    }

    @Test
    void selfUploadDesignInputsAreNormalizedAndFrozen() throws Exception {
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, Map.of(
                "faceWidthM", 12.6, "depthM", "13.8",
                "floor", "两层", "floorCount", 2,
                "family", "5室3厅2卫", "rooms", Map.of("bedroom", 5, "living", 3, "bath", 2),
                "prompt", "南向客厅", "note", "老人房在一楼"));
        var snapshot = snapshotOf(projectId);
        assertThat(snapshot)
                .containsEntry("faceWidthM", "12.6")
                .containsEntry("depthM", "13.8")
                .containsEntry("floor", "两层")
                .containsEntry("floorCount", 2)
                .containsEntry("family", "5室3厅2卫")
                .containsEntry("prompt", "南向客厅")
                .containsEntry("note", "老人房在一楼");
        @SuppressWarnings("unchecked")
        var rooms = (Map<String, Object>) snapshot.get("rooms");
        assertThat(rooms).containsEntry("bedroom", 5).containsEntry("living", 3).containsEntry("bath", 2);
    }

    @Test
    void unknownAndOutOfRangeDesignInputsAreRejected() {
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("floors", 2))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("runId", "x"))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("faceWidthM", 50))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("depthM", "abc"))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("floorCount", 5))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("note", "长".repeat(201)))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("rooms", Map.of("bedroom", 0)))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("rooms", Map.of("balcony", 1)))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("styleCode", "BAD STYLE!"))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
    }

    /** 纯单元层：立面任务配置键（count/roofType/material/color）与控制符剔除，无需容器 */
    @Test
    void elevationConfigKeysPassThroughDesignContract() {
        var validated = BudgetInputs.validateRequirementInputs(Map.of(
                "styleCode", "MODERN", "roofType", "gable", "material", "STONE",
                "color", "WARM_WHITE", "count", 2));
        assertThat(validated)
                .containsEntry("roofType", "gable")
                .containsEntry("count", 2);
        // 控制字符剔除而非整单拒绝（wxml textarea 允许换行）
        var cleaned = BudgetInputs.validateRequirementInputs(Map.of("note", "老人房在一楼\n保留露台"));
        assertThat(cleaned).containsEntry("note", "老人房在一楼保留露台");
        assertThatThrownBy(() -> BudgetInputs.validateRequirementInputs(Map.of("count", 5)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
        // 面宽下界 3 由后端强制
        assertThatThrownBy(() -> BudgetInputs.validateRequirementInputs(Map.of("faceWidthM", 1.5)))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(ERR_DESIGN_INPUT));
    }

    @Test
    void budgetInputsContractStillValidatesAsBefore() {
        assertThatThrownBy(() -> projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("budgetInputs", Map.of("regionCode", "!!")))).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(ERR_BUDGET_INPUT));
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, Map.of(
                "floor", "两层", "family", "5室3厅2卫", "prompt", "", "note", "",
                "budgetInputs", Map.of("regionCode", "VAR1", "footprintArea", "120", "floorCount", 2)));
        // 归一化后的 budgetInputs 与旧形状设计键共存于同一快照
        // （budgetInputs 的进一步断言由 T10 契约覆盖，这里确认整包未被拒绝且设计键原样保留）
        // 排序不保证，直接读回校验关键键。
        try {
            var snapshot = snapshotOf(projectId);
            assertThat(snapshot).containsEntry("floor", "两层").containsEntry("family", "5室3厅2卫");
            @SuppressWarnings("unchecked")
            var budget = (Map<String, Object>) snapshot.get("budgetInputs");
            assertThat(budget).containsEntry("regionCode", "VAR1").containsEntry("footprintArea", "120");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void caseReferenceMergesCaseDefaultsAndUserInputsWin() throws Exception {
        long caseId = seedPublishedCase(12, 10, 2);
        long withDefaults = projects.createProject(USER_A, "CASE_REFERENCE", caseId,
                null, Map.of("note", "老人房在一楼"));
        var defaults = snapshotOf(withDefaults);
        assertThat(defaults)
                .containsEntry("faceWidthM", "12")
                .containsEntry("depthM", "10")
                .containsEntry("floorCount", 2)
                .containsEntry("styleCode", "MODERN")
                .containsEntry("note", "老人房在一楼");

        long withUserInput = projects.createProject(USER_A, "CASE_REFERENCE", caseId,
                null, Map.of("faceWidthM", 15.5, "depthM", 11));
        var overridden = snapshotOf(withUserInput);
        assertThat(overridden)
                .containsEntry("faceWidthM", "15.5")
                .containsEntry("depthM", "11")
                .containsEntry("styleCode", "MODERN");
    }

    @Test
    void outOfRangeCaseDefaultsAreSkippedInsteadOfFailingCreation() throws Exception {
        long caseId = seedPublishedCase(99, null, 6);
        jdbc.update("UPDATE design_case_version SET style_code = 'bad style' WHERE id = "
                + "(SELECT current_version_id FROM design_case WHERE id = ?)", caseId);
        // 案例元数据越界：兜底键被跳过，项目仍创建成功且用户输入不受影响
        long projectId = projects.createProject(USER_A, "CASE_REFERENCE", caseId,
                null, Map.of("note", "老人房在一楼"));
        var snapshot = snapshotOf(projectId);
        assertThat(snapshot).doesNotContainKeys("faceWidthM", "depthM", "floorCount", "styleCode");
        assertThat(snapshot).containsEntry("note", "老人房在一楼");
    }

    @Test
    void listProjectsEnrichesCoverJobStatusAndResultFlag() {
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("floorCount", 2, "note", "老人房在一楼"));
        // 无任务/无选择：空封面、空任务状态、无结果
        var empty = projects.listProjects(USER_A, null, 20).list().get(0);
        assertThat(empty.coverAssetId()).isNull();
        assertThat(empty.jobStatus()).isNull();
        assertThat(empty.hasResult()).isFalse();

        // 手工落一条 RUNNING 任务 + 平面选择候选 + 资产与结果版本，验证列表富化
        long assetId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'AI_OUTPUT','AI','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                assetId, "ai/" + assetId + ".png", USER_A);
        long candidateId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long resultId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long selectionId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long jobId = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO design_candidate (id, project_id, job_id, slot_no, asset_id, ai_result_id) VALUES (?,?,?,1,?,?)",
                candidateId, projectId, jobId, assetId, resultId);
        jdbc.update("INSERT INTO ai_job (id, project_ref, user_id, phase, status, requested_count, output_prefix) "
                + "VALUES (?, ?, ?, 'FLAT', 'RUNNING', 1, ?)", jobId, String.valueOf(projectId), USER_A, "ai-quarantine/" + projectId);
        jdbc.update("INSERT INTO design_selection (id, project_id, stage, candidate_id, selected_by) "
                + "VALUES (?, ?, 'FLAT', ?, ?)", selectionId, projectId, candidateId, USER_A);
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids) "
                + "VALUES (?, ?, 1, ?, CAST(? AS jsonb))", com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), projectId, selectionId, "[1]");

        var enriched = projects.listProjects(USER_A, null, 20).list().get(0);
        assertThat(enriched.coverAssetId()).isEqualTo(String.valueOf(assetId));
        assertThat(enriched.jobStatus()).isEqualTo("RUNNING");
        assertThat(enriched.hasResult()).isTrue();
    }

    @Test
    void latestDesignInputsReturnsDesignKeysAndHidesBudget() {
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("faceWidthM", 12.6, "floor", "两层", "note", "老人房在一楼",
                        "budgetInputs", Map.of("regionCode", "VAR1")));
        var inputs = projects.latestDesignInputs(projectId);
        assertThat(inputs)
                .containsEntry("faceWidthM", "12.6")
                .containsEntry("floor", "两层")
                .containsEntry("note", "老人房在一楼");
        assertThat(inputs).doesNotContainKey("budgetInputs");
        assertThat(projects.latestDesignInputs(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId())).isEmpty();
    }

    /** 发布带 2 张平面图的公司案例（含生成参考授权），faceWidth/depth 可指定 */
    private long seedPublishedCase(Integer faceWidth, Integer depth, int floorCount) {
        long caseId = catalog.createCompanyCase(ADMIN, "参考案例", null, "MODERN", floorCount, 100,
                faceWidth, depth, null, null);
        long versionId = jdbc.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        long relation1 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long relation2 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long asset1 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        long asset2 = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                + "VALUES (?,?,?,'FLOOR_PLAN',1)", relation1, versionId, asset1);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                + "VALUES (?,?,?,'FLOOR_PLAN',2)", relation2, versionId, asset2);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                asset1, "company-cases/" + asset1 + ".png", ADMIN);
        jdbc.update("INSERT INTO asset (id, object_key, owner_user_id, asset_type, source_type, sha256, "
                + "declared_mime, size_bytes, upload_status, security_scan_status, moderation_status) "
                + "VALUES (?,?,?,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED')",
                asset2, "company-cases/" + asset2 + ".png", ADMIN);
        rights.createGrant(ADMIN, asset1, "GENERATION_REFERENCE", "平台", "*", "*",
                Instant.now(), null);
        rights.createGrant(ADMIN, asset2, "GENERATION_REFERENCE", "平台", "*", "*",
                Instant.now(), null);
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,'COVER')",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, asset1);
        rights.createGrant(ADMIN, asset1, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        rights.createGrant(ADMIN, asset2, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }
}
