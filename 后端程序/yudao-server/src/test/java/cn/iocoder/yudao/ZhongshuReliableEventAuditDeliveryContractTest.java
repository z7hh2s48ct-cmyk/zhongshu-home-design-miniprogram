package cn.iocoder.yudao;

import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobRequest;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobSnapshot;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.IssuedTicket;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxDispatcherService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventSink;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.OutboxEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.event.ReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1C 合同测试：可靠事件、审计与异步交付（真实 PostgreSQL）
 *
 * 覆盖蓝图验收：事务回滚不留事件、提交后至少一次投递、重复投递幂等、
 * dispatcher 崩溃重领、一次性票据过期/越权/不可重放、导出任务 CAS。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ZhongshuReliableEventAuditDeliveryContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ReliableEventPort eventPort;
    private AuditPort auditPort;
    private JdbcDeliveryPort deliveryPort;
    private OutboxDispatcherService dispatcher;
    private RecordingSink sink;

    private final List<String> delivered = new ArrayList<>();

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform")
                .load()
                .migrate();

        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        eventPort = new JdbcReliableEventPort(dataSource);
        auditPort = new JdbcAuditPort(dataSource);
        deliveryPort = new JdbcDeliveryPort(dataSource);
        sink = new RecordingSink(delivered);
        dispatcher = new OutboxDispatcherService(dataSource, List.of(sink));
    }

    @BeforeEach
    void cleanTables() {
        try (Connection conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("TRUNCATE outbox_event, audit_event, export_job, "
                    + "one_time_delivery_ticket, one_time_download_ticket, zhongshu_baseline_probe");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        delivered.clear();
        sink.failNext = false;
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
        return n == null ? -1 : n;
    }

    // ========== 1. 事务回滚不留事件 ==========

    @Test
    void businessRollbackMustAlsoRollbackOutboxAndAudit() {
        assertThatThrownBy(() -> tx.execute(status -> {
            jdbc.update("INSERT INTO zhongshu_baseline_probe (id, probe_name) VALUES (1, 'rollback_case')");
            eventPort.append(OutboxEventMessage.builder()
                    .eventType("TEST_EVENT").bizType("probe").bizId("1")
                    .payload(Map.of("k", "v")).build());
            auditPort.record(AuditEventMessage.builder()
                    .eventType("TEST_AUDIT").actorType(AuditEventMessage.ActorType.SYSTEM)
                    .action("CREATE").result(AuditEventMessage.AuditResult.SUCCESS).build());
            // 业务异常 → 整个事务回滚：业务行、outbox、审计必须一起消失
            throw new IllegalStateException("模拟业务异常");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(count("zhongshu_baseline_probe")).as("业务行应回滚").isZero();
        assertThat(count("outbox_event")).as("回滚不得留下 outbox 事件").isZero();
        assertThat(count("audit_event")).as("回滚不得留下审计事件").isZero();
    }

    // ========== 2. 提交后至少一次投递 + 重复领取幂等 ==========

    @Test
    void committedEventIsDeliveredAtLeastOnceAndNotRedeliveredAfterAck() {
        tx.execute(status -> {
            eventPort.append(OutboxEventMessage.builder()
                    .eventType("TEST_EVENT").bizType("probe").bizId("2")
                    .payload(Map.of("n", 2)).build());
            return null;
        });

        int claimed1 = dispatcher.dispatchOnce("main", "worker-1", 30, 10, 5);
        assertThat(claimed1).as("提交后事件必须可被领取").isEqualTo(1);
        assertThat(delivered).hasSize(1);

        int claimed2 = dispatcher.dispatchOnce("main", "worker-1", 30, 10, 5);
        assertThat(claimed2).as("已确认（DISPATCHED）的事件不得重复投递").isZero();
        assertThat(delivered).hasSize(1);
    }

    // ========== 3. Sink 失败退避 + 崩溃重领 ==========

    @Test
    void sinkFailureBacksOffAndExpiredLeaseAllowsReclaim() throws Exception {
        tx.execute(status -> {
            eventPort.append(OutboxEventMessage.builder()
                    .eventType("TEST_EVENT").bizType("probe").bizId("3")
                    .payload(Map.of()).build());
            return null;
        });

        sink.failNext = true;
        assertThat(dispatcher.dispatchOnce("main", "worker-1", 30, 10, 0)).isEqualTo(1);
        assertThat(delivered).as("失败的投递不记入成功").isEmpty();
        Integer retryCount = jdbc.queryForObject("SELECT retry_count FROM outbox_event", Integer.class);
        assertThat(retryCount).isEqualTo(1);

        // 实例 A 用长租约领取后“崩溃”：租约未过期时实例 B 领取不到
        List<OutboxEventRecord> claimedByA = dispatcher.claim("main", "worker-A", 3600, 10);
        assertThat(claimedByA).hasSize(1);
        assertThat(dispatcher.claim("main", "worker-B", 30, 10))
                .as("租约未过期不得被其他实例重领").isEmpty();

        // 模拟租约到期（崩溃实例不再续租）：B 可重领
        try (Connection conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = conn.createStatement()) {
            st.execute("UPDATE outbox_event SET claim_expires_at = now() - interval '1 second'");
        }
        assertThat(dispatcher.claim("main", "worker-B", 30, 10))
                .as("租约到期后事件必须可被重领").hasSize(1);
    }

    @Test
    void repeatedSinkFailuresEventuallyDead() throws Exception {
        tx.execute(status -> {
            eventPort.append(OutboxEventMessage.builder()
                    .eventType("TEST_EVENT").bizType("probe").bizId("4")
                    .payload(Map.of()).build());
            return null;
        });
        for (int i = 0; i < 5; i++) {
            sink.failNext = true;
            assertThat(dispatcher.dispatchOnce("main", "worker-1", 30, 10, 0)).isEqualTo(1);
        }
        String status = jdbc.queryForObject("SELECT status FROM outbox_event", String.class);
        assertThat(status).as("连续 5 次失败后必须进入 DEAD 人工处置").isEqualTo("DEAD");
        assertThat(dispatcher.claim("main", "worker-2", 30, 10))
                .as("DEAD 事件不再被领取").isEmpty();
    }

    // ========== 4. 一次性票据：一次性、不可重放、可区分过期/未知 ==========

    @Test
    void oneTimeTicketIsConsumableExactlyOnce() throws Exception {
        IssuedTicket issued = deliveryPort.issueDeliveryTicket("ACCESS_CODE_BATCH", "batch-1", 99L, 60);
        assertThat(issued.getToken()).isNotBlank();
        assertThat(issued.getExpiresAt()).isNotNull();

        // 库中 ticket_hash 必须等于明文 token 的 SHA-256（明文本身不落库）
        String expectedHash;
        try (Connection conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT ticket_hash FROM one_time_delivery_ticket WHERE biz_ref = 'batch-1'")) {
            rs.next();
            expectedHash = rs.getString(1);
        }
        byte[] tokenBytes = issued.getToken().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String sha256 = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(tokenBytes));
        assertThat(expectedHash).as("库中只存 token 的 SHA-256").isEqualTo(sha256);

        TicketConsumption first = deliveryPort.consumeDeliveryTicket(issued.getToken(), "user-1");
        assertThat(first.getOutcome()).isEqualTo(TicketConsumption.Outcome.CONSUMED_NOW);
        assertThat(first.getBizRef()).isEqualTo("batch-1");

        assertThat(deliveryPort.consumeDeliveryTicket(issued.getToken(), "user-2").getOutcome())
                .isEqualTo(TicketConsumption.Outcome.ALREADY_CONSUMED);
        assertThat(deliveryPort.consumeDeliveryTicket("not-a-real-token", "user-2").getOutcome())
                .isEqualTo(TicketConsumption.Outcome.UNKNOWN);

        // 交付票据与下载票据按用途隔离
        assertThat(deliveryPort.consumeDownloadTicket(issued.getToken(), "user-2").getOutcome())
                .isEqualTo(TicketConsumption.Outcome.UNKNOWN);

        // 过期票据
        IssuedTicket expired = deliveryPort.issueDeliveryTicket("ACCESS_CODE_BATCH", "batch-2", 99L, -1);
        assertThat(deliveryPort.consumeDeliveryTicket(expired.getToken(), "user-1").getOutcome())
                .isEqualTo(TicketConsumption.Outcome.EXPIRED);
    }

    // ========== 5. 导出任务 CAS 与快照 ==========

    @Test
    void exportJobTransitionsAreCasGuarded() {
        long jobId = deliveryPort.createExportJob(ExportJobRequest.builder()
                .jobType("POINT_LEDGER").requesterType("ADMIN").requesterUserId(7L)
                .filterSnapshot(Map.of("type", "MANUAL_CREDIT")).build());
        assertThat(deliveryPort.getExportJob(jobId).getStatus())
                .isEqualTo(ExportJobSnapshot.Status.PENDING);

        assertThat(deliveryPort.completeExportJob(jobId, "asset-1", "sha256:abc", 600)).isTrue();
        ExportJobSnapshot done = deliveryPort.getExportJob(jobId);
        assertThat(done.getStatus()).isEqualTo(ExportJobSnapshot.Status.COMPLETED);
        assertThat(done.getFileAssetId()).isEqualTo("asset-1");
        assertThat(done.getFilterSnapshot()).containsEntry("type", "MANUAL_CREDIT");

        assertThat(deliveryPort.completeExportJob(jobId, "asset-2", "sha256:xyz", 600))
                .as("终态后不得重复完成").isFalse();
        assertThat(deliveryPort.failExportJob(jobId, "late"))
                .as("终态后不得再转失败").isFalse();
    }

    /** 记录型 Sink，可注入一次性失败 */
    private static class RecordingSink implements OutboxEventSink {

        private final List<String> delivered;
        boolean failNext;

        private RecordingSink(List<String> delivered) {
            this.delivered = delivered;
        }

        @Override
        public boolean supports(String eventType) {
            return "TEST_EVENT".equals(eventType);
        }

        @Override
        public void deliver(OutboxEventRecord event) {
            if (failNext) {
                failNext = false;
                throw new IllegalStateException("模拟投递失败");
            }
            delivered.add(event.getEventId() + ":" + event.getPayload());
        }

    }

}
