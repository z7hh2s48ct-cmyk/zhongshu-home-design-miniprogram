package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxDispatcherService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P7B 合同测试（真实 PostgreSQL）：预算规则版本化与历史不漂移、消息 Outbox→Sink 落库+未读数+已读幂等、
 * 无 Sink 支持的事件不产生消息。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetMessageP7BContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private BudgetService budget;
    private MessageService messages;
    private OutboxDispatcherService dispatcher;
    private ReliableEventPort eventPort;

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
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load()
                .migrate();

        // These tests only use project creation/read; AI/assets/rights are not called.
        var projects = new DesignProjectService(dataSource, new DataSourceTransactionManager(dataSource),
                null, null, null, null);
        budget = new BudgetService(dataSource, projects);
        messages = new MessageService(dataSource);
        dispatcher = new OutboxDispatcherService(dataSource, List.of(messages));
        eventPort = new JdbcReliableEventPort(dataSource);
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE budget_quote, budget_line, budget_revision, budget_rule_version, budget_estimate, user_message, message_receipt, "
                + "outbox_event");
        jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES "
                + "(100, 1, 'SELF_UPLOAD'), (101, 1, 'SELF_UPLOAD'), (102, 1, 'SELF_UPLOAD') ON CONFLICT (id) DO NOTHING");
    }

    @Test
    void messagesCanContinuePastFiftyAndReceiptCannotTargetAnotherUser() {
        for (long id = 1; id <= 55; id++) jdbc.update("INSERT INTO user_message (id,user_id,message_type,title) VALUES (?,1,'AI_JOB_SETTLED','test')", id);
        jdbc.update("INSERT INTO user_message (id,user_id,message_type,title) VALUES (100,2,'AI_JOB_SETTLED','private')");
        var first = messages.listPage(1, null, 50);
        assertThat(first.list()).hasSize(50);
        assertThat(first.nextCursor()).isEqualTo("6");
        var last = messages.listPage(1, first.nextCursor(), 50);
        assertThat(last.list()).hasSize(5);
        assertThat(last.nextCursor()).isNull();
        assertThat(messages.markRead(1, 1)).isTrue();
        assertThat(messages.markRead(1, 1)).isFalse();
        assertThatThrownBy(() -> messages.markRead(1, 100)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(() -> messages.listPage(1, "broken", 50)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(messages.unreadCount(1)).isEqualTo(54);
    }

    // ========== 1. 预算：规则版本化，历史不漂移 ==========

    @Test
    void budgetEstimatesDoNotDriftAfterRuleChange() {
        budget.createRuleVersion("VAR1", "BRICK", "A", 300_00L, 500_00L, Instant.now().minusSeconds(60));
        long oldArea = 120;
        var first = budget.createEstimate(1L, 100L, "VAR1", "BRICK", "A", (int) oldArea, null);
        long oldLow = first.totalLowCents();
        assertThat(oldLow).isEqualTo(300_00L * oldArea);

        // 规则调价后：新测算按新价，旧测算原样
        budget.createRuleVersion("VAR1", "BRICK", "A", 400_00L, 600_00L, Instant.now());
        var later = budget.createEstimate(1L, 101L, "VAR1", "BRICK", "A", (int) oldArea, null);
        assertThat(later.totalLowCents()).isEqualTo(400_00L * oldArea);

        var reloaded = budget.getEstimate(1L, first.estimateId()).orElseThrow();
        assertThat(reloaded.totalLowCents()).as("历史预算按保存快照展示，不漂移").isEqualTo(oldLow);
        assertThat(reloaded.inputSnapshot()).containsEntry("buildingArea", (Object) (int) oldArea);

        // 无规则地区拒绝
        assertThatThrownBy(() -> budget.createEstimate(1L, 102L, "NOPE", "BRICK", "A", 100, null))
                .isInstanceOf(ServiceException.class);
    }

    // ========== 2. 消息：Outbox → Sink 落库 + 未读数 + 已读幂等 ==========

    @Test
    void outboxDrivesMessagesWithUnreadAndReadReceipts() {
        // 业务事务写事件（payload 携带 userId）
        eventPort.append(OutboxEventMessage.builder()
                .eventType("AI_JOB_SETTLED").bizType("ai_job").bizId("job-1")
                .payload(Map.of("jobId", 1L, "userId", 42L, "status", "SUCCEEDED")).build());

        int claimed = dispatcher.dispatchOnce("main", "worker-1", 30, 10, 5);
        assertThat(claimed).isEqualTo(1);
        assertThat(messages.unreadCount(42L)).isEqualTo(1);

        var list = messages.list(42L, 10);
        assertThat(list).hasSize(1);
        boolean read = (Boolean) list.get(0).get("read");
        assertThat(read).isFalse();

        // 已读回执幂等：重复标记不再减少未读
        long messageId = ((Number) list.get(0).get("id")).longValue();
        assertThat(messages.markRead(42L, messageId)).isTrue();
        assertThat(messages.markRead(42L, messageId)).isFalse();
        assertThat(messages.unreadCount(42L)).isZero();

        // 事件已确认不重复投递
        assertThat(dispatcher.dispatchOnce("main", "worker-1", 30, 10, 5)).isZero();
        assertThat(messages.unreadCount(42L)).isZero();
    }

    // ========== 3. 白名单外事件不产生消息（事件本身仍被确认） ==========

    @Test
    void nonWhitelistedEventsProduceNoMessageButAcked() {
        eventPort.append(OutboxEventMessage.builder()
                .eventType("SOME_FUTURE_EVENT").bizType("x").bizId("1")
                .payload(Map.of("userId", 42L)).build());
        assertThat(dispatcher.dispatchOnce("main", "worker-1", 30, 10, 0)).isEqualTo(1);
        assertThat(messages.unreadCount(42L)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_message", Integer.class)).isZero();
    }

}
