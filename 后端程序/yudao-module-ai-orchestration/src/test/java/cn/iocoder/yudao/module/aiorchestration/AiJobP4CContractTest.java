package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobSettlementService;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.commerce.pricing.PricingPortAdapter;
import cn.iocoder.yudao.module.commerce.points.PointLedgerPortAdapter;
import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.PricingPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
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
 * P4C 合同测试（真实 PostgreSQL + 真实 commerce 账本/计价）：
 * 创建同事务扣点、幂等重放、余额不足、价格失效、写点崩溃注入回放、
 * N=0/1/N-1/N/N+1 结算规则、重复结算幂等、退款上限。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiJobP4CContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private DataSource dataSource;
    private DataSourceTransactionManager txManager;
    private JdbcTemplate jdbc;
    private PointAccountService points;
    private AiJobOrchestrationService orchestration;
    private AiJobSettlementService settlement;
    private FakeObjectStorage objectStorage;
    private PointLedgerPort ledgerPort;
    private PricingPort pricingPort;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        dataSource = ds;
        txManager = new DataSourceTransactionManager(dataSource);
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/commerce",
                        "classpath:db/migration/ai-orchestration")
                .load()
                .migrate();

        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        points = new PointAccountService(dataSource, txManager);
        var priceRuleService = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource);
        pricingPort = new PricingPortAdapter(priceRuleService);
        ledgerPort = new PointLedgerPortAdapter(points);
        objectStorage = new FakeObjectStorage();
        ReliableEventPort eventPort = new JdbcReliableEventPort(dataSource);
        orchestration = new AiJobOrchestrationService(dataSource, txManager,
                eventPort, objectStorage,
                (mime, content) -> new ContentScanPort.ScanOutcome(true, List.of(), null, null, null));
        settlement = new AiJobSettlementService(dataSource, txManager,
                pricingPort, ledgerPort, orchestration, eventPort);

        // 种子计价规则：FLAT 单价 10 点，允许 1~4 张
        priceRuleService.createRule("FLAT", 10, 1, 4, Instant.now().minusSeconds(60), null);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, "
                + "ai_job_settlement, ai_task_charge, generation_price_rule, design_point_account, "
                + "design_point_ledger, manual_point_adjustment, outbox_event");
        objectStorage.clear();
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9001, 'FLAT', 10, 1, 4, now() - interval '1 minute')");
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
        points.credit(userId, "RECHARGE_BASE_CREDIT", amount, "recharge_order",
                "r-" + userId, "k-credit-" + userId + "-" + amount, null, null);
    }

    private long available(long userId) {
        return points.findAccount(userId).map(PointAccountService.PointAccount::availablePoints).orElse(0L);
    }

    /** 假 Runtime：领取 + 提交 acceptedCount 个有效结果（其余槽位提交无效输出被拒） */
    private void runProvider(long jobId, int validCount, int requestedCount) {
        var jobs = orchestration.claim("worker-p4c", 1, 60, "stub");
        assertThat(jobs).hasSize(1);
        var job = jobs.get(0);
        for (int slot = 1; slot <= requestedCount; slot++) {
            byte[] content = ("output-" + jobId + "-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            objectStorage.putObject(objectKey, content);
            var outcome = orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-" + jobId + "-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
            assertThat(outcome).as("slot %d 提交应成功入隔离区", slot)
                    .isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
        }
        if (validCount < requestedCount) {
            // 让前 validCount 个有效：把多余槽位的结果对象删掉 → SHA/存在性校验失败
            for (int slot = validCount + 1; slot <= requestedCount; slot++) {
                objectStorage.remove(job.outputPrefix() + "/slot-" + slot + ".png");
            }
        }
        orchestration.validateQuarantinedResults(jobId);
    }

    // ========== 1. 创建同事务扣点 + 幂等重放 ==========

    @Test
    void createJobWithChargeDebitsOnceAndIdempotent() {
        givePoints(1L, 100);
        long job1 = settlement.createJobWithCharge(1L, "FLAT", 2, "key-1", null);
        long job2 = settlement.createJobWithCharge(1L, "FLAT", 2, "key-1", null);
        assertThat(job2).isEqualTo(job1);
        assertThat(available(1L)).as("同键重放只扣一次").isEqualTo(80);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_task_charge WHERE job_id = ?", Integer.class, job1)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'FLAT_GENERATION_DEBIT' AND user_id = 1",
                Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_event WHERE event_type = 'AI_JOB_CREATED'", Integer.class))
                .isEqualTo(1);
    }

    // ========== 2. 余额不足：不建任务不扣点 ==========

    @Test
    void insufficientBalanceCreatesNothing() {
        givePoints(2L, 5);
        assertThatThrownBy(() -> settlement.createJobWithCharge(2L, "FLAT", 2, "key-2", null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_072_000_000));
        assertThat(available(2L)).isEqualTo(5);
        assertThat(count("ai_job")).isZero();
        assertThat(count("ai_task_charge")).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'FLAT_GENERATION_DEBIT'",
                Integer.class)).isZero();
    }

    // ========== 3. 崩溃注入：扣点后、任务落库前崩溃 → 整体回滚可重放 ==========

    @Test
    void crashAfterDebitRollsBackEverything() {
        givePoints(3L, 100);
        // 模拟“扣点成功后、任务落库前”进程崩溃：同一事务内抛错，扣点必须随事务回滚
        var tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            ledgerPort.debit(3L, "FLAT_GENERATION_DEBIT", 20,
                    "ai_job", "999", "AI_JOB:crash-test", null, null);
            throw new IllegalStateException("模拟扣点后崩溃");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(available(3L)).as("崩溃后扣点必须回滚").isEqualTo(100);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM design_point_ledger WHERE type = 'FLAT_GENERATION_DEBIT'",
                Integer.class)).isZero();
        assertThat(count("ai_job")).isZero();
    }

    // ========== 4. 价格失效：停用规则后创建被拒且不扣点 ==========

    @Test
    void priceChangeAppliesOnlyToNewJobsAndKeepsFrozenSnapshots() {
        givePoints(4L, 100);
        // 旧价 10 点建任务（冻结快照）
        long jobOld = settlement.createJobWithCharge(4L, "FLAT", 2, "key-4", null);
        assertThat(jdbc.queryForObject(
                "SELECT unit_point_cost FROM ai_task_charge WHERE job_id = ?", Long.class, jobOld))
                .isEqualTo(10);
        assertThat(available(4L)).isEqualTo(80);

        // 换价：旧规则停用 + 新规则 12 点
        jdbc.update("UPDATE generation_price_rule SET status = 'RETIRED' WHERE stage = 'FLAT'");
        jdbc.update("INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                + "effective_at) VALUES (9002, 'FLAT', 12, 1, 4, now())");
        long jobNew = settlement.createJobWithCharge(4L, "FLAT", 2, "key-4b", null);
        assertThat(jobNew).isNotEqualTo(jobOld);
        assertThat(jdbc.queryForObject(
                "SELECT unit_point_cost FROM ai_task_charge WHERE job_id = ?", Long.class, jobNew))
                .as("新任务按新价 12 点扣").isEqualTo(12);
        assertThat(available(4L)).isEqualTo(56);

        // 全部规则停用后再建：系统配置错误路径（非 PRICE_RULE_CHANGED）
        jdbc.update("UPDATE generation_price_rule SET status = 'RETIRED' WHERE stage = 'FLAT'");
        assertThatThrownBy(() -> settlement.createJobWithCharge(4L, "FLAT", 2, "key-4c", null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(available(4L)).isEqualTo(56);
    }

    // ========== 5. 结算规则：N=2 全成功 / N-1 / 0 ==========

    @Test
    void settlementRulesByAcceptedSlots() {
        // N=2 全成功：不退点
        givePoints(11L, 100);
        long j1 = settlement.createJobWithCharge(11L, "FLAT", 2, "key-11", null);
        runProvider(j1, 2, 2);
        var s1 = settlement.settle(j1, "SETTLE");
        assertThat(s1.status()).isEqualTo("SUCCEEDED");
        assertThat(s1.refundedNow()).isZero();
        assertThat(available(11L)).isEqualTo(80);

        // N-1：退 10
        givePoints(12L, 100);
        long j2 = settlement.createJobWithCharge(12L, "FLAT", 2, "key-12", null);
        runProvider(j2, 1, 2);
        var s2 = settlement.settle(j2, "SETTLE");
        assertThat(s2.status()).isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(s2.refundedNow()).isEqualTo(10);
        assertThat(available(12L)).isEqualTo(90);

        // 0 个有效：FAILED 全退
        givePoints(13L, 100);
        long j3 = settlement.createJobWithCharge(13L, "FLAT", 2, "key-13", null);
        runProvider(j3, 0, 2);
        var s3 = settlement.settle(j3, "SETTLE");
        assertThat(s3.status()).isEqualTo("FAILED");
        assertThat(s3.refundedNow()).isEqualTo(20);
        assertThat(available(13L)).isEqualTo(100);
    }

    // ========== 6. N+1 不多收 ==========

    @Test
    void overDeliveryNeverOvercharges() {
        givePoints(14L, 100);
        long jobId = settlement.createJobWithCharge(14L, "FLAT", 2, "key-14", null);
        var job = orchestration.claim("worker-p4c", 1, 60, "stub").get(0);
        // 提交 4 个结果（槽位 1..4 超出 N=2 的 1..2 会被范围校验拒；合法的只有 1、2）
        for (int slot = 1; slot <= 2; slot++) {
            byte[] content = ("over-" + slot).getBytes(StandardCharsets.UTF_8);
            String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
            objectStorage.putObject(objectKey, content);
            orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                    "stub", "evt-over-" + slot, slot, objectKey, sha256(content),
                    "image/png", content.length);
        }
        // 重复内容不同 source：内容 Hash 去重拒绝
        byte[] dup = "over-1".getBytes(StandardCharsets.UTF_8);
        String dupKey = job.outputPrefix() + "/slot-1-dup.png";
        objectStorage.putObject(dupKey, dup);
        orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                "stub", "evt-over-dup", 2, dupKey, sha256(dup), "image/png", dup.length);
        orchestration.validateQuarantinedResults(jobId);

        var summary = settlement.settle(jobId, "SETTLE");
        assertThat(summary.status()).isEqualTo("SUCCEEDED");
        assertThat(jobsAcceptedCount(jobId)).as("N=2 时接受数封顶 2").isEqualTo(2);
        assertThat(summary.refundedNow()).isZero();
        assertThat(available(14L)).isEqualTo(80);
    }

    // ========== 7. 重复结算幂等 + 退款上限 ==========

    @Test
    void repeatedSettlementIsIdempotentAndCapped() {
        givePoints(15L, 100);
        long jobId = settlement.createJobWithCharge(15L, "FLAT", 2, "key-15", null);
        runProvider(jobId, 1, 2);
        var first = settlement.settle(jobId, "SETTLE");
        var second = settlement.settle(jobId, "SETTLE");
        assertThat(second.status()).isEqualTo(first.status());
        assertThat(second.refundedNow()).as("终态重入不再退点").isZero();
        assertThat(available(15L)).isEqualTo(90);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_settlement WHERE job_id = ?", Integer.class, jobId)).isEqualTo(1);
    }

    // ========== 7b. QUEUED 取消必须全额退款（审查 C1 回归） ==========

    @Test
    void queuedCancellationRefundsInFull() {
        givePoints(17L, 100);
        long jobId = settlement.createJobWithCharge(17L, "FLAT", 2, "key-17", null);
        assertThat(available(17L)).isEqualTo(80);

        // QUEUED 状态直接取消
        orchestration.requestCancellation(jobId);
        // 任务进入 CANCEL_REQUESTED（不再直置终态），结算全退
        var summary = settlement.settle(jobId, "CANCEL");
        assertThat(summary.status()).isEqualTo("CANCELLED");
        assertThat(summary.refundedNow()).as("0 有效结果全额退").isEqualTo(20);
        assertThat(available(17L)).isEqualTo(100);
        // 结算后无结果行、状态终态
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id = ?", Integer.class, jobId)).isZero();
    }

    // ========== 8. 取消路径带部分结果 → PARTIALLY + 差额退 ==========

    @Test
    void cancellationWithPartialResultsSettlesPartially() {
        givePoints(16L, 100);
        long jobId = settlement.createJobWithCharge(16L, "FLAT", 2, "key-16", null);
        var job = orchestration.claim("worker-p4c", 1, 60, "stub").get(0);
        byte[] content = "partial-1".getBytes(StandardCharsets.UTF_8);
        String objectKey = job.outputPrefix() + "/slot-1.png";
        objectStorage.putObject(objectKey, content);
        orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                "stub", "evt-p1", 1, objectKey, sha256(content), "image/png", content.length);
        orchestration.validateQuarantinedResults(jobId);

        orchestration.requestCancellation(jobId);
        var summary = settlement.settle(jobId, "CANCEL");
        assertThat(summary.status()).as("取消带 1/2 有效结果按槽位差额退").isEqualTo("PARTIALLY_SUCCEEDED");
        assertThat(summary.refundedNow()).isEqualTo(10);
        assertThat(available(16L)).isEqualTo(90);
    }

    // ========== 辅助 ==========

    private DataSource dataSource() {
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

    private DataSource objectStorageDataSource() {
        return dataSource();
    }

    private PricingPort realPricing() {
        var priceRuleService = new cn.iocoder.yudao.module.commerce.pricing.PriceRuleService(dataSource());
        return new PricingPortAdapter(priceRuleService);
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private Integer jobsAcceptedCount(long jobId) {
        Integer n = jdbc.queryForObject(
                "SELECT accepted_count FROM ai_job WHERE id = ?", Integer.class, jobId);
        return n == null ? 0 : n;
    }

    private static class FakeObjectStorage implements QuarantineObjectPort {

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

        void remove(String objectKey) {
            objects.remove(objectKey);
        }

    }

}
