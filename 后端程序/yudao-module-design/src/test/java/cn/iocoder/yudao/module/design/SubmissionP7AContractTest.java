package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.design.asset.AssetContentScanner;
import cn.iocoder.yudao.module.design.asset.AssetService;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.design.submission.SubmissionReviewService;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P7A 合同测试（真实 PostgreSQL 全真实链）：校核拒绝、投稿幂等、自审拒绝、退修→重提→复审、
 * 独立发布命令生成 AI 案例（重复发布拒绝）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmissionP7AContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long USER_A = 5501L;
    private static final long REVIEWER = 6601L;

    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private SubmissionReviewService submissions;
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
        var rights = new RightsGrantService(dataSource);
        quarantine = new FakeQuarantineStorage();
        orchestration = new AiJobOrchestrationService(dataSource, txManager, eventPort, quarantine,
                (mime, content) -> new cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort.ScanOutcome(
                        true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager, pricingPort, ledgerPort,
                orchestration, eventPort);
        AiJobPort aiJobPort = new AiJobPortAdapter(settlement, orchestration);
        var assetService = new AssetService(dataSource, txManager,
                new LocalObjectStorageAdapter(java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),
                        "p7-assets-" + System.nanoTime()).toString()),
                new AssetContentScanner(), new StubContentModerationAdapter(),
                new cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort(dataSource), rights);
        projects = new DesignProjectService(dataSource, txManager, aiJobPort, assetService, rights, quarantine);
        var catalog = new CaseCatalogService(dataSource, txManager);
        submissions = new SubmissionReviewService(dataSource, txManager, catalog, eventPort);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE case_submission, submission_revision, review_task, review_decision, "
                + "design_case, design_case_version, design_case_asset, case_publication, case_favorite, "
                + "design_project, design_requirement_snapshot, design_candidate, design_selection, "
                + "design_result_version, design_revision_request, asset_rights_grant, "
                + "ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, ai_job_settlement, "
                + "ai_task_charge, generation_price_rule, design_point_account, design_point_ledger, "
                + "outbox_event, asset CASCADE");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9301, 'FLAT', 10, 1, 2, now() - interval '1 minute')");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9302, 'ELEVATION', 10, 1, 2, now() - interval '1 minute')");
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void givePoints(long userId) {
        var points = new cn.iocoder.yudao.module.commerce.points.PointAccountService(
                ds(), new DataSourceTransactionManager(ds()));
        points.credit(userId, "RECHARGE_BASE_CREDIT", 500, "recharge_order", "r-" + userId,
                "k-seed-" + userId + "-" + System.nanoTime(), null, null);
    }

    private DataSource ds() {
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

    private void runJob(long jobId, int validCount) {
        var job = orchestration.claim("worker-p7", 1, 60, "stub").get(0);
        for (int slot = 1; slot <= validCount; slot++) {
            byte[] content = ("p7-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            quarantine.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
        }
        orchestration.validateQuarantinedResults(jobId);
        settlement.settle(jobId, "SETTLE");
    }

    /** 全链生成项目并产出最终结果版本（v1） */
    private long seedResultVersion(long userId) {
        givePoints(userId);
        long projectId = projects.createProject(userId, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(userId, projectId, 1, "flat-" + projectId);
        runJob(flat.jobId(), 1);
        var flatCandidates = projects.promoteCandidates(userId, projectId, flat.jobId());
        projects.selectFlatCandidate(userId, projectId, flatCandidates.get(0).candidateId());
        var elev = projects.createElevationJob(userId, projectId, 1, "elev-" + projectId,
                Map.of("styleCode", "MODERN"));
        runJob(elev.jobId(), 1);
        var elevCandidates = projects.promoteCandidates(userId, projectId, elev.jobId()).stream()
                .filter(c -> c.jobId() == elev.jobId()).toList();
        return projects.selectElevationCandidate(userId, projectId, elevCandidates.get(0).candidateId())
                .versionId();
    }

    // ========== 1. 校核：无立面选择不可提交 ==========

    @Test
    void validationRejectsIncompleteSubmission() {
        givePoints(USER_A);
        long projectId = projects.createProject(USER_A, "SELF_UPLOAD", null, null, null);
        var flat = projects.createFlatJob(USER_A, projectId, 1, "flat-v");
        runJob(flat.jobId(), 1);
        var flatCandidates = projects.promoteCandidates(USER_A, projectId, flat.jobId());
        projects.selectFlatCandidate(USER_A, projectId, flatCandidates.get(0).candidateId());
        // 平面阶段即提交：无立面 → 校核失败
        assertThatThrownBy(() -> submissions.submit(USER_A, projectId, 999999L,
                true, false, null, "sub-x"))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_071_000_002));
    }

    // ========== 2. 提交幂等 + 自审拒绝 + 退修重提 + 发布闭环 ==========

    @Test
    void fullSubmissionReviewPublishLoop() {
        long versionId = seedResultVersion(USER_A);

        // 提交（幂等）
        long sub1 = submissions.submit(USER_A, jdbc.queryForObject(
                "SELECT project_id FROM design_result_version WHERE id = ?", Long.class, versionId),
                versionId, true, true, "投稿说明", "sub-key-1");
        long sub2 = submissions.submit(USER_A, jdbc.queryForObject(
                "SELECT project_id FROM design_result_version WHERE id = ?", Long.class, versionId),
                versionId, true, true, "投稿说明", "sub-key-1");
        assertThat(sub2).isEqualTo(sub1);

        // 自审拒绝
        assertThatThrownBy(() -> submissions.decide(sub1, USER_A, "APPROVE", null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_002));

        // 要求修改 → 重提 → 通过
        submissions.decide(sub1, REVIEWER, "CHANGES_REQUESTED", "补充说明文案");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM case_submission WHERE id = ?", String.class, sub1))
                .isEqualTo("CHANGES_REQUESTED");
        submissions.resubmit(USER_A, sub1, "已补充");
        assertThat(jdbc.queryForObject(
                "SELECT current_round FROM case_submission WHERE id = ?", Integer.class, sub1))
                .isEqualTo(2);
        submissions.decide(sub1, REVIEWER, "APPROVE", null);

        // APPROVED 但未发布：AI 案例库不可见
        assertThat(count("design_case WHERE source_type = 'AI'")).isZero();

        // 独立发布命令 → AI 案例上架；重复发布拒绝
        long caseId = submissions.publishApprovedAsCase(sub1, "admin");
        assertThat(count("design_case WHERE source_type = 'AI'")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT publication_status FROM design_case WHERE id = ?",
                String.class, caseId)).isEqualTo("PUBLISHED");
        assertThatThrownBy(() -> submissions.publishApprovedAsCase(sub1, "admin"))
                .isInstanceOf(ServiceException.class);

        // 已发布 AI 案例对小程序可见（P3B 列表）
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_case WHERE source_type = 'AI' AND publication_status = 'PUBLISHED'",
                Integer.class)).isEqualTo(1);
    }

    // ========== 3. 未 APPROVED 不可发布 ==========

    @Test
    void publishRequiresApproval() {
        long versionId = seedResultVersion(USER_A);
        long sub = submissions.submit(USER_A, jdbc.queryForObject(
                "SELECT project_id FROM design_result_version WHERE id = ?", Long.class, versionId),
                versionId, true, false, null, "sub-key-2");
        assertThatThrownBy(() -> submissions.publishApprovedAsCase(sub, "admin"))
                .isInstanceOf(ServiceException.class);
    }

    private int count(String condition) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + condition, Integer.class);
        return n == null ? 0 : n;
    }

    private static class FakeQuarantineStorage implements QuarantineObjectPort {

        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

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

}
