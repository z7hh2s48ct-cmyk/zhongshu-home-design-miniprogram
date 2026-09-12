package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetQuarantineAdapter;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P6 合同测试（真实 PostgreSQL 全真实链）：未选平面不能生成立面、跨项目候选拒绝、
 * 立面选择生成不可变最终版本、调整请求生成新版本（旧版本只读）、重复选择幂等。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResultVersionP6ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 3301L;

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private FakeQuarantineStorage quarantine;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration", "classpath:db/migration/design")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        var points = new cn.iocoder.yudao.module.commerce.points.PointAccountService(dataSource, txManager);
        var priceRules = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        var pricingPort = new cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter(priceRules);
        var ledgerPort = new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(points);
        var eventPort = new JdbcReliableEventPort(dataSource);
        quarantine = new FakeQuarantineStorage();
        var rights = new RightsGrantService(dataSource);
        orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var objectStorage = new LocalObjectStorageAdapter(
                java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "p6-assets-" + System.nanoTime())
                        .toString());
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_candidate, "
                + "design_selection, design_result_version, design_revision_request, "
                + "ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, ai_job_settlement, "
                + "ai_task_charge, generation_price_rule, design_point_account, design_point_ledger, outbox_event");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9201, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9202, 'ELEVATION', 15, 1, 4, now() - interval '1 minute')");
        quarantine.clear();
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givePoints(long userId, long amount) {
        var points = new cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter(
                new cn.iocoder.yudao.module.commerce.points.PointAccountService(
                        objectDataSource(), new DataSourceTransactionManager(objectDataSource())));
        points.credit(userId, "RECHARGE_BASE_CREDIT", amount, "recharge_order", "r-" + userId,
                "k-seed-" + userId + "-" + amount, null, null);
    }

    private DataSource objectDataSource() {
        try {
            var ds = new SimpleDriverDataSource();
            ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(PG.getJdbcUrl());
            ds.setUsername(PG.getUsername());
            ds.setPassword(PG.getPassword());
            return ds;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void runElevation(long jobId, int validCount) {
        var job = orchestration.claim("worker-p6", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("p6-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    /** 完整走到「已选定平面」的项目 */
    private long projectWithFlatSelected() {
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(USER_A, projectId, 2, "flat-" + projectId, confirmed("FLAT"));
        runElevationJobAsFlat(flat.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, flat.jobId());
        projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        return projectId;
    }

    private void runElevationJobAsFlat(long jobId, int validCount) {
        runElevation(jobId, validCount); // 假 Runtime 与阶段无关
    }

    // ========== 1. 未选平面不能生成立面任务 ==========

    @Test
    void elevationJobRequiresFlatSelection() {
        givePoints(USER_A, 500);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        assertThatThrownBy(() -> projects.createElevationJob(USER_A, projectId, 2, "elev-x", null, confirmed("ELEVATION")))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_000));
    }

    // ========== 2. 立面选择生成不可变最终版本；调整链生成新版本 ==========

    @Test
    void elevationSelectionCreatesVersionAndRevisionCreatesNext() {
        long projectId = projectWithFlatSelected();

        // 立面任务 + 选择 → v1
        var elev1 = projects.createElevationJob(USER_A, projectId, 2, "elev-1",
                Map.of("styleCode", "MODERN", "roofType", "gable"), confirmed("ELEVATION"));
        runElevation(elev1.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, elev1.jobId());
        var v1 = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(v1.version()).isEqualTo(1);

        // 版本列表：1 条，未被取代
        var versions = projects.listResultVersions(USER_A, projectId);
        assertThat(versions).hasSize(1);

        // 调整请求 → 新立面任务 → 选择 → v2（旧版本只读保留）
        var revision = projects.createRevisionRequest(USER_A, projectId, "屋顶改双坡",
                Map.of("roofType", "hip"), 1, "elev-2", confirmed("ELEVATION"));
        runElevation(revision.newJobId(), 1);
        var allAfterRevision = projects.promoteCandidates(USER_A, projectId, revision.newJobId());
        var candidates2 = allAfterRevision.stream()
                .filter(c -> c.jobId() == revision.newJobId()).toList();
        var v2 = projects.selectElevationCandidate(USER_A, projectId, candidates2.get(0).candidateId());
        assertThat(v2.version()).isEqualTo(2);

        versions = projects.listResultVersions(USER_A, projectId);
        assertThat(versions).hasSize(2);
        // 旧版本内容只读：v1 行仍是 v1 的内容（快照未变）
        assertThat(jdbc.queryForObject(
                "SELECT version FROM design_result_version WHERE id = ?", Long.class, v1.versionId()))
                .isEqualTo(1);
        // 调整请求完成
        assertThat(jdbc.queryForObject(
                "SELECT status FROM design_revision_request WHERE id = ?",
                String.class, revision.requestId())).isEqualTo("COMPLETED");
        // 新版本活跃，旧版本 superseded
        assertThat(jdbc.queryForObject(
                "SELECT superseded FROM design_result_version WHERE id = ?", Boolean.class, v1.versionId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT superseded FROM design_result_version WHERE id = ?", Boolean.class, v2.versionId()))
                .isFalse();
    }

    // ========== 3. 重复立面选择幂等 ==========

    @Test
    void repeatedElevationSelectionIsIdempotent() {
        long projectId = projectWithFlatSelected();
        var elev1 = projects.createElevationJob(USER_A, projectId, 2, "elev-i1", null, confirmed("ELEVATION"));
        runElevation(elev1.jobId(), 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, elev1.jobId());

        var first = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        var again = projects.selectElevationCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(again.versionId()).isEqualTo(first.versionId());
        assertThat(again.version()).isEqualTo(first.version());
        assertThat(projects.listResultVersions(USER_A, projectId)).hasSize(1);
    }

    // ========== 4. BOLA：B 不可读/操作 A 的项目版本 ==========

    @Test
    void crossUserResultAccessRejected() {
        long projectId = projectWithFlatSelected();
        long USER_B = 4402L;
        assertThatThrownBy(() -> projects.listResultVersions(USER_B, projectId))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.createElevationJob(USER_B, projectId, 1, "elev-b", null, confirmed("ELEVATION")))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 5. 无最终版本时调整请求拒绝 ==========

    @Test
    void revisionRequiresExistingVersion() {
        long projectId = projectWithFlatSelected();
        assertThatThrownBy(() -> projects.createRevisionRequest(USER_A, projectId, "改屋顶",
                null, 1, "rev-x", confirmed("ELEVATION")))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_000));
    }

    @Test
    void recoveredVersionAssetsUseFrozenSelectionsAndRemainOwnerScoped() {
        long projectId = projectWithFlatSelected();
        var firstJob = projects.createElevationJob(USER_A, projectId, 1, "recover-one", null, confirmed("ELEVATION"));
        runElevation(firstJob.jobId(), 1);
        var firstCandidate = projects.promoteCandidates(USER_A, projectId, firstJob.jobId()).stream()
                .filter(c -> c.jobId() == firstJob.jobId()).findFirst().orElseThrow();
        var firstVersion = projects.selectElevationCandidate(USER_A, projectId, firstCandidate.candidateId());
        Long flatAsset = jdbc.queryForObject("SELECT c.asset_id FROM design_selection s JOIN design_candidate c "
                + "ON c.id = s.candidate_id WHERE s.project_id = ? AND s.stage = 'FLAT' AND s.deleted = FALSE", Long.class, projectId);
        var second = projects.createRevisionRequest(USER_A, projectId, "改立面", null, 1, "recover-two", confirmed("ELEVATION"));
        runElevation(second.newJobId(), 1);
        var nextCandidate = projects.promoteCandidates(USER_A, projectId, second.newJobId()).stream()
                .filter(c -> c.jobId() == second.newJobId()).findFirst().orElseThrow();
        projects.selectElevationCandidate(USER_A, projectId, nextCandidate.candidateId());
        var rows = projects.listResultVersions(USER_A, projectId);
        var old = rows.stream().filter(r -> ((Number) r.get("id")).longValue() == firstVersion.versionId()).findFirst().orElseThrow();
        assertThat(old.get("selected_flat_asset_id")).isEqualTo(flatAsset);
        assertThat(old.get("selected_elevation_asset_id")).isEqualTo(firstCandidate.assetId());
        assertThat(rows.get(0).get("selected_elevation_asset_id")).isEqualTo(nextCandidate.assetId());
        assertThatThrownBy(() -> projects.listResultVersions(9999, projectId)).isInstanceOf(ServiceException.class);
    }

    private static class FakeQuarantineStorage implements QuarantineObjectPort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        void clear() {
            objects.clear();
        }

        @Override
        public boolean existsWithSize(String objectKey, long expectedSize) {
            byte[] data = objects.get(objectKey);
            return data != null && data.length == expectedSize;
        }

        @Override
        public byte[] getObject(String objectKey) {
            byte[] data = objects.get(objectKey);
            if (data == null) {
                throw new IllegalStateException("对象不存在: " + objectKey);
            }
            return data;
        }

        @Override
        public void putObject(String objectKey, byte[] content) {
            objects.put(objectKey, content);
        }

    }


    private cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation confirmed(String stage) {
        var rows=jdbc.queryForList("SELECT id,version FROM generation_price_rule WHERE stage=? ORDER BY effective_at DESC,id DESC LIMIT 1",stage);
        var row=rows.get(0);
        return new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation(String.valueOf(row.get("id")),((Number)row.get("version")).longValue());
    }
}
