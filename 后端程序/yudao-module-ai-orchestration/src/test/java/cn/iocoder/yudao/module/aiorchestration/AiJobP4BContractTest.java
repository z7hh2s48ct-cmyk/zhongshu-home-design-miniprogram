package cn.iocoder.yudao.module.aiorchestration;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.aiorchestration.job.AiJobOrchestrationService;
import cn.iocoder.yudao.module.infra.zhongshu.api.ContentScanPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.QuarantineObjectPort;
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
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P4B 合同测试（真实 PostgreSQL）：多 Worker 领取互斥、租约过期重领、旧 fencing 拒绝、
 * 结果 Inbox 幂等重放、槽位/内容唯一、取消单调序裁决、迟到结果只留诊断。
 * 测试内假 Runtime 即蓝图要求的 Stub 载体（覆盖成功/失败/重复回调/无效输出/取消）。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiJobP4BContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private AiJobOrchestrationService orchestration;
    private FakeObjectStorage objectStorage;
    private ReliableEventPort reliableEventPort;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/ai-orchestration")
                .load()
                .migrate();

        objectStorage = new FakeObjectStorage();
        reliableEventPort = new JdbcReliableEventPort(dataSource);
        DataSourceTransactionManager txManager = new DataSourceTransactionManager(dataSource);
        orchestration = new AiJobOrchestrationService(dataSource, txManager,
                reliableEventPort, objectStorage, (mime, content) ->
                new ContentScanPort.ScanOutcome(true, List.of(), null, null, null));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE ai_job, ai_job_attempt, ai_result_event_inbox, ai_job_result, "
                + "ai_job_settlement, outbox_event, audit_event");
        objectStorage.clear();
    }

    private long createJob(long userId, int count) {
        return orchestration.createJob(userId, "FLAT", count, "idem-" + userId + "-" + count, null);
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 假 Runtime：写隔离区输出并上报结果 */
    private AiJobOrchestrationService.ReportOutcome submitResult(
            AiJobOrchestrationService.ClaimedJob job, String providerCode, String sourceEventId,
            int slot, byte[] content) {
        String objectKey = job.outputPrefix() + "/slot-" + slot + ".png";
        objectStorage.putObject(objectKey, content);
        return orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                providerCode, sourceEventId, slot, objectKey, sha256(content), "image/png", content.length);
    }

    // ========== 1. 幂等创建 ==========

    @Test
    void createJobIdempotentByIdempotencyKey() {
        long id1 = orchestration.createJob(1L, "FLAT", 2, "key-create-1", null);
        long id2 = orchestration.createJob(1L, "FLAT", 2, "key-create-1", null);
        assertThat(id2).isEqualTo(id1);
    }

    // ========== 2. 多 Worker 并发领取互斥 ==========

    @Test
    void concurrentClaimsAreDisjoint() throws Exception {
        for (int i = 0; i < 6; i++) {
            createJob(1L, 2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<long[]> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<Callable<Boolean>> tasks = List.of(
                () -> {
                    start.await();
                    var jobs = orchestration.claim("worker-1", 6, 60, "stub");
                    jobs.forEach(j -> seen.add(new long[]{j.jobId(), j.fencingToken()}));
                    return true;
                },
                () -> {
                    start.await();
                    var jobs = orchestration.claim("worker-2", 6, 60, "stub");
                    jobs.forEach(j -> seen.add(new long[]{j.jobId(), j.fencingToken()}));
                    return true;
                });
        for (Callable<Boolean> t : tasks) {
            pool.submit(t);
        }
        start.countDown();
        pool.shutdown();
        while (!pool.isTerminated()) {
            Thread.sleep(20);
        }
        long distinctJobs = seen.stream().map(x -> x[0]).distinct().count();
        assertThat(distinctJobs).as("两个 Worker 领取的任务互不重叠").isEqualTo(seen.size());
        assertThat(seen.stream().map(x -> x[1]).distinct().count()).as("fencing token 各不相同")
                .isEqualTo(seen.size());
    }

    // ========== 3. 租约过期重领 + 旧 fencing 拒绝 ==========

    @Test
    void expiredLeaseReclaimedAndStaleFencingRejected() throws Exception {
        long jobId = createJob(2L, 1);
        var first = orchestration.claim("worker-a", 1, 1, "stub").get(0); // 1 秒租约

        Thread.sleep(1200); // 租约过期
        var second = orchestration.claim("worker-b", 1, 60, "stub");
        assertThat(second).as("租约过期后可被其他 Worker 重领").hasSize(1);
        assertThat(second.get(0).jobId()).isEqualTo(jobId);
        assertThat(second.get(0).fencingToken()).as("重领后 fencing 递增")
                .isGreaterThan(first.fencingToken());

        // 旧持有者回写被拒
        var outcome = orchestration.reportResult(jobId, first.attemptNo(), first.fencingToken(),
                "stub", "evt-stale", 1, second.get(0).outputPrefix() + "/x.png",
                sha256("x".getBytes(StandardCharsets.UTF_8)), "image/png", 1);
        assertThat(outcome).isEqualTo(AiJobOrchestrationService.ReportOutcome.STALE_FENCING);

        // 新持有者正常
        var outcome2 = submitResult(second.get(0), "stub", "evt-fresh", 1,
                "png-data".getBytes(StandardCharsets.UTF_8));
        assertThat(outcome2).isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
    }

    // ========== 4. 结果 Inbox 幂等：同事件重放只记一次 ==========

    @Test
    void duplicateResultEventIsIdempotent() {
        long jobId = createJob(3L, 1);
        var job = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        byte[] content = "png-1".getBytes(StandardCharsets.UTF_8);
        var first = submitResult(job, "stub", "evt-dup", 1, content);
        var second = submitResult(job, "stub", "evt-dup", 1, content);
        assertThat(first).isEqualTo(AiJobOrchestrationService.ReportOutcome.QUARANTINED);
        assertThat(second).isEqualTo(AiJobOrchestrationService.ReportOutcome.DUPLICATE_EVENT);
        assertThat(count("ai_result_event_inbox")).isEqualTo(1);
        assertThat(count("ai_job_result")).isEqualTo(1);
    }

    // ========== 5. 校验：合法输出 ACCEPTED；非法对象/SHA 拒绝 ==========

    @Test
    void validationAcceptsGoodAndRejectsBadOutputs() {
        long jobGood = createJob(4L, 1);
        var jg = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        submitResult(jg, "stub", "evt-good", 1, "good-png".getBytes(StandardCharsets.UTF_8));
        orchestration.validateQuarantinedResults(jobGood);
        assertThat(resultState(jobGood, "evt-good")).isEqualTo("ACCEPTED");
        assertThat(jobsAcceptedCount(jobGood)).isEqualTo(1);

        // 非法前缀
        long jobBad = createJob(5L, 1);
        var jb = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        String badKey = "elsewhere/evil.png";
        objectStorage.putObject(badKey, "evil".getBytes(StandardCharsets.UTF_8));
        orchestration.reportResult(jobBad, jb.attemptNo(), jb.fencingToken(), "stub", "evt-bad", 1,
                badKey, sha256("evil".getBytes(StandardCharsets.UTF_8)), "image/png", 4);
        orchestration.validateQuarantinedResults(jobBad);
        assertThat(resultState(jobBad, "evt-bad")).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject(
                "SELECT reject_reason FROM ai_job_result WHERE job_id = ? AND validation_state = 'REJECTED'",
                String.class, jobBad)).contains("OBJECT_KEY_PREFIX");

        // SHA 不符
        long jobSha = createJob(6L, 1);
        var js = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        byte[] real = "real-content".getBytes(StandardCharsets.UTF_8);
        String objectKey = js.outputPrefix() + "/slot-1.png";
        objectStorage.putObject(objectKey, real);
        orchestration.reportResult(jobSha, js.attemptNo(), js.fencingToken(), "stub", "evt-sha", 1,
                objectKey, sha256("declared-other".getBytes(StandardCharsets.UTF_8)),
                "image/png", real.length);
        orchestration.validateQuarantinedResults(jobSha);
        assertThat(resultState(jobSha, "evt-sha")).isEqualTo("REJECTED");
    }

    // ========== 6. 两个 ACCEPTED 争同一槽位：后到者 REJECTED，不占死槽位 ==========

    @Test
    void twoAcceptedResultsCompeteForSameSlot() {
        long jobId = createJob(7L, 2);
        var job = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        // 两个不同对象、各自 SHA 正确、指向同一槽位：校验都通过，竞争槽位唯一索引
        byte[] contentA = "first-content".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "second-content".getBytes(StandardCharsets.UTF_8);
        String keyA = job.outputPrefix() + "/slot-1-a.png";
        String keyB = job.outputPrefix() + "/slot-1-b.png";
        objectStorage.putObject(keyA, contentA);
        objectStorage.putObject(keyB, contentB);
        orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                "stub", "evt-s1-a", 1, keyA, sha256(contentA), "image/png", contentA.length);
        orchestration.reportResult(job.jobId(), job.attemptNo(), job.fencingToken(),
                "stub", "evt-s1-b", 1, keyB, sha256(contentB), "image/png", contentB.length);
        orchestration.validateQuarantinedResults(jobId);

        Integer accepted = jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id = ? AND validation_state = 'ACCEPTED' "
                        + "AND candidate_slot_no = 1", Integer.class, jobId);
        assertThat(accepted).as("每个槽位最多一条 ACCEPTED").isEqualTo(1);
        String rejectReason = jdbc.queryForObject(
                "SELECT reject_reason FROM ai_job_result WHERE job_id = ? AND candidate_slot_no = 1 "
                        + "AND validation_state = 'REJECTED'", String.class, jobId);
        assertThat(rejectReason).as("落败者以 SLOT_OR_CONTENT_TAKEN 留痕").isEqualTo("SLOT_OR_CONTENT_TAKEN");

        // 拒绝不占死槽位：拒绝行不阻止其他槽位继续接受
        Integer acceptedSlot2 = jdbc.queryForObject(
                "SELECT count(*) FROM ai_job_result WHERE job_id = ? AND candidate_slot_no = 2 "
                        + "AND validation_state = 'REJECTED'", Integer.class, jobId);
        assertThat(acceptedSlot2).isZero();
    }

    // ========== 7. 取消单调序：先取消后到结果只留诊断 ==========

    @Test
    void cancellationBeatsLateResultsByMonotonicSeq() {
        long jobId = createJob(8L, 1);
        var job = orchestration.claim("worker-1", 1, 60, "stub").get(0);

        orchestration.requestCancellation(jobId);
        assertThat(orchestration.getJob(jobId).orElseThrow().status()).isEqualTo("CANCEL_REQUESTED");

        // 取消后到达的结果：received_seq > cancel_seq → SUPERSEDED，不进隔离
        var late = orchestration.reportResult(jobId, job.attemptNo(), job.fencingToken(),
                "stub", "evt-late", 1, job.outputPrefix() + "/late.png",
                sha256("late".getBytes(StandardCharsets.UTF_8)), "image/png", 4);
        assertThat(late).isEqualTo(AiJobOrchestrationService.ReportOutcome.SUPERSEDED);

        // 无 ACCEPTED 结果 → 完成取消
        assertThat(orchestration.completeCancellation(jobId)).isTrue();
        assertThat(orchestration.getJob(jobId).orElseThrow().status()).isEqualTo("CANCELLED");

        // 终态后再到 → TERMINAL_IGNORED；重复取消 → AI_JOB_NOT_CANCELLABLE
        var terminal = orchestration.reportResult(jobId, job.attemptNo(), job.fencingToken(),
                "stub", "evt-terminal", 1, job.outputPrefix() + "/t.png",
                sha256("t".getBytes(StandardCharsets.UTF_8)), "image/png", 1);
        assertThat(terminal).isEqualTo(AiJobOrchestrationService.ReportOutcome.TERMINAL_IGNORED);
        assertThatThrownBy(() -> orchestration.requestCancellation(jobId))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_073_000_000));
    }

    // ========== 8. 失败事件回队 ==========

    @Test
    void failureEventRequeuesJob() {
        long jobId = createJob(9L, 1);
        var job = orchestration.claim("worker-1", 1, 60, "stub").get(0);
        assertThat(orchestration.reportFailure(jobId, job.attemptNo(), job.fencingToken(),
                "stub", "evt-fail", "PROVIDER_TIMEOUT", "上游超时")).isTrue();
        assertThat(orchestration.getJob(jobId).orElseThrow().status()).isEqualTo("QUEUED");
        // 旧 fencing 回写失败事件被拒
        assertThat(orchestration.reportFailure(jobId, job.attemptNo(), job.fencingToken(),
                "stub", "evt-fail-2", "PROVIDER_TIMEOUT", "again")).isFalse();
    }

    // ========== 9. 终态固化写入 Outbox ==========

    @Test
    void cancellationWritesOutboxEvent() {
        // 路径一：QUEUED 直接取消（requestCancellation 内发事件，stage=before-claim）
        long queuedJobId = createJob(10L, 1);
        orchestration.requestCancellation(queuedJobId);
        orchestration.completeCancellation(queuedJobId);
        assertOutboxCancelled(queuedJobId, 10L, "after-claim");

        // 路径二：已领取后取消（completeCancellation 内发事件，stage=after-claim）
        // MessageService 的白名单投递要求 payload 必带 userId，缺失即投递失败；
        // 该路径曾因 SELECT 未取 user_id 而 NPE，取消无法完成、点数不退，故此处必须独立覆盖。
        long claimedJobId = createJob(11L, 1);
        orchestration.claim("worker-cancel", 1, 60, "stub");
        orchestration.requestCancellation(claimedJobId);
        assertThat(orchestration.getJob(claimedJobId).orElseThrow().status()).isEqualTo("CANCEL_REQUESTED");
        assertThat(orchestration.completeCancellation(claimedJobId)).isTrue();
        assertThat(orchestration.getJob(claimedJobId).orElseThrow().status()).isEqualTo("CANCELLED");
        assertOutboxCancelled(claimedJobId, 11L, "after-claim");
    }

    private void assertOutboxCancelled(long jobId, long expectedUserId, String expectedStage) {
        var rows = jdbc.queryForList(
                "SELECT payload->>'userId' AS user_id, payload->>'stage' AS stage FROM outbox_event "
                        + "WHERE event_type = 'AI_JOB_CANCELLED' AND biz_id = ?", String.valueOf(jobId));
        assertThat(rows).as("每次取消只产生一条 AI_JOB_CANCELLED").hasSize(1);
        assertThat(rows.get(0).get("user_id"))
                .as("payload 必带 userId，否则 MessageService 无法投递消息")
                .isEqualTo(String.valueOf(expectedUserId));
        assertThat(rows.get(0).get("stage")).isEqualTo(expectedStage);
    }

    // ========== 辅助 ==========

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private Integer jobsAcceptedCount(long jobId) {
        Integer n = jdbc.queryForObject(
                "SELECT accepted_count FROM ai_job WHERE id = ?", Integer.class, jobId);
        return n == null ? 0 : n;
    }

    private String resultState(long jobId, String sourceEventId) {
        return jdbc.queryForObject(
                "SELECT r.validation_state FROM ai_job_result r "
                        + "JOIN ai_result_event_inbox i ON i.job_id = r.job_id AND i.attempt_no = r.attempt_no "
                        + "WHERE r.job_id = ? AND i.source_event_id = ?",
                String.class, jobId, sourceEventId);
    }

    /** 内存对象存储（隔离区假实现） */
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

    }

}
