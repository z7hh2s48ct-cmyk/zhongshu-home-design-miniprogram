package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetQuarantineAdapter;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P5 合同测试（真实 PostgreSQL，全真实模块链）：
 * 页面 04~07 主链——建项目→平面任务扣点→Runtime 产出→结算→候选晋升→选择；
 * 仅公开案例不可生成、授权撤回后新任务拒绝、幂等键防重复点击、对象级权限。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignProjectP5ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 1101L;
    private static final long USER_B = 2202L;
    private static final long ADMIN = 9901L;

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private CaseCatalogService catalog;
    private RightsGrantService rights;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private PointAccountService points;
    private FakeQuarantineStorage quarantine;

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
        points = new PointAccountService(dataSource, txManager);
        var priceRules = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        var pricingPort = new PricingPortAdapter(priceRules);
        var ledgerPort = new PointLedgerPortAdapter(points);
        var eventPort = new JdbcReliableEventPort(dataSource);
        quarantine = new FakeQuarantineStorage();
        rights = new RightsGrantService(dataSource);
        catalog = new CaseCatalogService(dataSource, txManager);
        orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var objectStorage = new LocalObjectStorageAdapter(
                Files.createTempDirectory("p5-assets").toString());
        var assetService = new AssetService(dataSource, txManager, objectStorage,
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine);
    }

    private void seedPriceRule() {
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9101, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
    }

    @Test
    void confirmedPriceMustMatchActualChargeAndRetryKeepsOriginalJob() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "price", "seed-price", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var price = new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation("9101", 1);
        jdbc.update("UPDATE generation_price_rule SET unit_point_cost=15, version=2 WHERE id=9101");
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, projectId, 2, "price-key", price))
                .isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_001));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job", Long.class)).isZero();
        var accepted = new cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort.PriceConfirmation("9101", 2);
        long job = projects.createFlatJob(USER_A, projectId, 2, "price-key", accepted).jobId();
        assertThat(jdbc.queryForObject("SELECT total_point_cost FROM ai_task_charge WHERE job_id=?", Long.class, job)).isEqualTo(30);
        jdbc.update("UPDATE generation_price_rule SET version=3 WHERE id=9101");
        assertThat(projects.createFlatJob(USER_A, projectId, 2, "price-key", accepted).jobId()).isEqualTo(job);
        assertThat(jdbc.queryForObject("SELECT available_points FROM design_point_account WHERE user_id=?", Long.class, USER_A)).isEqualTo(70);
    }

    @Test
    void projectRecoveryFindsOwnedLatestJobAndMovesToElevationAfterFlatSelection() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "resume", "seed-resume", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var controller = new cn.iocoder.yudao.module.design.controller.app.AppDesignProjectController();
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "designProjectService", projects);
        cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort identities = token -> java.util.Optional.of(
                new cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort.SessionContext(USER_A, "app", "id", false));
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        long jobId = projects.createFlatJob(USER_A, projectId, 2, "resume-job", confirmed("FLAT")).jobId();
        var running = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(running.getJobId()).isEqualTo(String.valueOf(jobId));
        assertThat(running.getResumeAction()).isEqualTo("POLL_JOB");
        jdbc.update("UPDATE ai_job SET status='SUCCEEDED', accepted_count=2 WHERE id=?", jobId);
        assertThat(controller.getProject(String.valueOf(projectId), null, "owner").getData().getResumeAction()).isEqualTo("SELECT_FLAT");
        jdbc.update("INSERT INTO design_selection (id,project_id,stage,candidate_id,selected_by) VALUES (8801,?,'FLAT',8802,?)", projectId, USER_A);
        var selected = controller.getProject(String.valueOf(projectId), null, "owner").getData();
        assertThat(selected.getResumeAction()).isEqualTo("CREATE_ELEVATION_JOB");
        assertThat(selected.getJobId()).isNull();
        assertThat(selected.getStage()).isEqualTo("ELEVATION");
        assertThat(orchestration.latestJob(USER_B, projectId, "FLAT")).isEmpty();
        assertThat(orchestration.latestJob(USER_A, projectId + 1, "FLAT")).isEmpty();
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_project, design_requirement_snapshot, design_candidate, "
                + "design_selection, ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, "
                + "ai_job_settlement, ai_task_charge, design_case, design_case_version, design_case_asset, "
                + "case_publication, asset_rights_grant, design_point_account, design_point_ledger, "
                + "generation_price_rule, outbox_event, asset CASCADE");
        seedPriceRule();
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

    /** 发布带 2 张平面图的公司案例，可选拨权生成参考 */
    private long seedPublishedCase(boolean grantGenerationReference) {
        long caseId = catalog.createCompanyCase(ADMIN, "参考案例", null, "MODERN", 2, 100,
                null, null, null, null);
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
        if (grantGenerationReference) {
            rights.createGrant(ADMIN, asset1, "GENERATION_REFERENCE", "平台", "*", "*",
                    Instant.now(), null);
            rights.createGrant(ADMIN, asset2, "GENERATION_REFERENCE", "平台", "*", "*",
                    Instant.now(), null);
        }
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,'COVER')",
                com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), versionId, asset1);
        rights.createGrant(ADMIN, asset1, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        rights.createGrant(ADMIN, asset2, "PUBLIC_DISPLAY", "平台", "*", "*", Instant.now(), null);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    /** 假 Runtime：领取任务、产出 N 个有效结果、校验、结算 */
    private void runProviderToSettled(long jobId, int validCount, int requested) {
        var job = orchestration.claim("worker-p5", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("p5-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            var outcome = orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
            assertThat(outcome).isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
        }
        for (int slot = validCount + 1; slot <= requested; slot++) {
            byte[] content = ("p5-bad-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-bad-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
            quarantine.remove(objectKey); // 对象缺失 → 校验拒绝
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    private long available(long userId) {
        return points.findAccount(userId)
                .map(PointAccountService.PointAccount::availablePoints).orElse(0L);
    }

    // ========== 1. 自主设计主链：建项目→任务扣点→产出→候选晋升→选择 ==========

    @Test
    void selfUploadFlatChainEndToEnd() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r",
                "k-seed-a", null, null);

        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null,
                Map.of("family", "三室两厅", "floorCount", 2));
        var created = projects.createFlatJob(USER_A, projectId, 2, "flat-key-1", confirmed("FLAT"));
        assertThat(available(USER_A)).as("创建任务扣 2×10 点").isEqualTo(80);

        // 幂等键防重复点击
        var replay = projects.createFlatJob(USER_A, projectId, 2, "flat-key-1", confirmed("FLAT"));
        assertThat(replay.jobId()).isEqualTo(created.jobId());
        assertThat(available(USER_A)).isEqualTo(80);

        // 未终态不可晋升/不可选择
        assertThatThrownBy(() -> projects.promoteCandidates(USER_A, projectId, created.jobId()))
                .isInstanceOf(IllegalStateException.class);

        runProviderToSettled(created.jobId(), 2, 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());
        assertThat(candidates).hasSize(2);

        long selectionId = projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        // 同候选重复选择：幂等返回同一 selection
        long same = projects.selectFlatCandidate(USER_A, projectId, candidates.get(0).candidateId());
        assertThat(same).isEqualTo(selectionId);
        // 换选（审查对称性修复）：旧选择失效，新选择生效
        projects.selectFlatCandidate(USER_A, projectId, candidates.get(1).candidateId());
        assertThat(jdbc.queryForObject(
                "SELECT candidate_id FROM design_selection WHERE project_id = ? AND stage = 'FLAT' "
                        + "AND active = TRUE", Long.class, projectId))
                .isEqualTo(candidates.get(1).candidateId());

        // 晋升的候选资产属用户且可走下载链
        assertThat(jdbc.queryForObject(
                "SELECT owner_user_id FROM asset WHERE id = ?",
                Long.class, candidates.get(0).assetId())).isEqualTo(USER_A);
    }

    // ========== 2. 参考案例：有生成参考授权才能建任务；冻结快照；他人不可见 ==========

    @Test
    void caseReferenceRequiresGenerationReferenceGrant() {
        long grantedCase = seedPublishedCase(true);
        long ungrantedCase = seedPublishedCase(false);

        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r2", "k-seed-b", null, null);

        // 仅公开（无生成参考授权）→ 建项目可以，建任务被拒
        long projectId = projects.createProject(USER_A, "CASE_REFERENCE", ungrantedCase, null, null);
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, projectId, 2, "flat-x", confirmed("FLAT")))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_003));
        assertThat(available(USER_A)).as("拒绝时不扣点").isEqualTo(100);

        // 有授权 → 建任务成功并冻结授权快照
        long projectId2 = projects.createProject(USER_A, "CASE_REFERENCE", grantedCase, null, null);
        var created = projects.createFlatJob(USER_A, projectId2, 2, "flat-ok", confirmed("FLAT"));
        assertThat(created.jobId()).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT rights_grant_id FROM design_project WHERE id = ?", Long.class, projectId2))
                .isNotNull();

        // 授权撤回后新任务拒绝（已建任务凭快照不受影响）
        long grantId = jdbc.queryForObject(
                "SELECT id FROM asset_rights_grant WHERE asset_id = (SELECT MIN(asset_id) "
                        + "FROM design_case_asset WHERE case_version_id = (SELECT ref_version_id "
                        + "FROM design_project WHERE id = ?)) LIMIT 1", Long.class, projectId2);
        rights.withdraw(grantId, "admin");
        assertThatThrownBy(() -> projects.createFlatJob(USER_A, projectId2, 2, "flat-after-revoke", confirmed("FLAT")))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 3. 对象级权限：B 拿不到 A 的项目/候选/选择 ==========

    @Test
    void crossUserAccessRejected() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r3", "k-seed-c", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var created = projects.createFlatJob(USER_A, projectId, 1, "flat-b", confirmed("FLAT"));
        runProviderToSettled(created.jobId(), 1, 1);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());

        assertThat(projects.getProject(projectId, USER_B)).isEmpty();
        assertThatThrownBy(() -> projects.promoteCandidates(USER_B, projectId, created.jobId()))
                .isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> projects.selectFlatCandidate(USER_B, projectId,
                candidates.get(0).candidateId()))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 4. 部分结果：N-1 候选 + 差额退点 ==========

    @Test
    void partialResultsPromoteAndRefundDifference() {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r4", "k-seed-d", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var created = projects.createFlatJob(USER_A, projectId, 2, "flat-partial", confirmed("FLAT"));

        runProviderToSettled(created.jobId(), 1, 2);
        var candidates = projects.promoteCandidates(USER_A, projectId, created.jobId());
        assertThat(candidates).hasSize(1);
        assertThat(available(USER_A)).as("差额退 10 点").isEqualTo(90);
    }

    // ========== 5. 并发重复点击：同幂等键只建一单扣一次 ==========

    @Test
    void concurrentDuplicateClicksCreateOneJob() throws Exception {
        points.credit(USER_A, "RECHARGE_BASE_CREDIT", 100, "recharge_order", "r5", "k-seed-e", null, null);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return projects.createFlatJob(USER_A, projectId, 2, "flat-race", confirmed("FLAT")).jobId();
            }));
        }
        start.countDown();
        long first = futures.get(0).get();
        for (Future<Long> f : futures) {
            assertThat(f.get()).isEqualTo(first);
        }
        pool.shutdownNow();
        assertThat(available(USER_A)).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE project_ref = ?",
                Integer.class, String.valueOf(projectId))).isEqualTo(1);
    }

    /** 内存隔离区 */
    private static class FakeQuarantineStorage implements QuarantineObjectPort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        void clear() {
            objects.clear();
        }

        void remove(String objectKey) {
            objects.remove(objectKey);
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
