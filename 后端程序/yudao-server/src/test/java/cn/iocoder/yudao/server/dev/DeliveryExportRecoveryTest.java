package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeliveryExportRecoveryTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    SimpleDriverDataSource ds; JdbcTemplate jdbc; JdbcDeliveryPort delivery;
    ObjectStoragePort storage; ZhongshuExportWorker worker; Map<String,byte[]> objects; MessageService messages;
    @BeforeAll void setup() {
        ds=new SimpleDriverDataSource(); ds.setDriverClass(org.postgresql.Driver.class); ds.setUrl(PG.getJdbcUrl()); ds.setUsername(PG.getUsername()); ds.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce").load().migrate();
        delivery=new JdbcDeliveryPort(ds); messages=new MessageService(ds);
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE export_job,design_point_ledger,audit_event,user_message,message_receipt,refund_order,recharge_order,case_submission");
        storage=mock(ObjectStoragePort.class);objects=new ConcurrentHashMap<>();
        doAnswer(call->{objects.put(call.getArgument(0),call.getArgument(1));return null;}).when(storage).putObject(anyString(),any());
        worker=new ZhongshuExportWorker(ds,delivery,storage);
    }
    long job(Map<String,Object> filters) {
        return job("POINT_LEDGER", filters);
    }
    long job(String jobType, Map<String,Object> filters) {
        return delivery.createExportJob(ExportJobRequest.builder().jobType(jobType).requesterType("ADMIN").requesterUserId(1L).filterSnapshot(filters).build());
    }
    void ledger(long id,long user,String reason) {
        jdbc.update("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after,reason) VALUES(?,?,'MANUAL_CREDIT',1,1,0,?)",id,user,reason);
    }
    String content(long id) {return new String(objects.get(delivery.getExportJob(id).getFileAssetId()),StandardCharsets.UTF_8);}
    @Test void appliesUserTypeTimeFiltersAcrossPagesAndNeutralizesFormulas() {
        for(long i=1;i<=601;i++) ledger(i,7,"=SAFE_MARKER_"+i);
        ledger(700,8,"OTHER_USER_SHOULD_NOT_APPEAR");
        ledger(701,7,"OUTSIDE_DATE_SHOULD_NOT_APPEAR");jdbc.update("UPDATE design_point_ledger SET create_time='2000-01-01' WHERE id=701");
        long id=job(Map.of("userId","7","type","MANUAL_CREDIT","from","2020-01-01T00:00:00Z"));
        worker.processClaim(id);
        assertThat(delivery.getExportJob(id).getStatus().name()).isEqualTo("COMPLETED");
        String csv=content(id);assertThat(csv).contains("\"'=SAFE_MARKER_601\"").doesNotContain("SHOULD_NOT_APPEAR");
        assertThat(csv.lines().count()).isEqualTo(602);
    }
    @Test void auditExportAppliesEventTypeAndTimeFilters() {
        jdbc.update("INSERT INTO audit_event(event_type,actor_type,action,create_time) VALUES('ORDER_CREDITED','SYSTEM','CREDIT','2026-01-02T00:00:00Z')");
        jdbc.update("INSERT INTO audit_event(event_type,actor_type,action,create_time) VALUES('ORDER_REFUND_REVERSED','SYSTEM','REFUND','2026-01-02T00:00:00Z')");
        jdbc.update("INSERT INTO audit_event(event_type,actor_type,action,create_time) VALUES('ORDER_CREDITED','SYSTEM','CREDIT','2000-01-01T00:00:00Z')");
        long id=job("AUDIT_EVENTS", Map.of("type","ORDER_CREDITED","from","2026-01-01T00:00:00Z"));
        worker.processClaim(id);
        assertThat(delivery.getExportJob(id).getStatus().name()).isEqualTo("COMPLETED");
        assertThat(content(id)).contains("ORDER_CREDITED").doesNotContain("ORDER_REFUND_REVERSED");
    }
    @Test void exportsBudgetEstimateAndAiPromptLedgerTypes() {
        ledger(1,7,"budget");jdbc.update("UPDATE design_point_ledger SET type='BUDGET_ESTIMATE_DEBIT' WHERE id=1");
        ledger(2,7,"prompt");jdbc.update("UPDATE design_point_ledger SET type='AI_PROMPT_DEBIT' WHERE id=2");
        long budget=job(Map.of("type","BUDGET_ESTIMATE_DEBIT"));
        long prompt=job(Map.of("type","AI_PROMPT_DEBIT"));
        worker.processClaim(budget);worker.processClaim(prompt);
        assertThat(content(budget)).contains("BUDGET_ESTIMATE_DEBIT").doesNotContain("AI_PROMPT_DEBIT");
        assertThat(content(prompt)).contains("AI_PROMPT_DEBIT").doesNotContain("BUDGET_ESTIMATE_DEBIT");
    }
    @Test void expiredLeaseCanBeRecoveredAndCannotOverwriteNewOwner() {
        ledger(1,7,"row");long id=job(Map.of());
        jdbc.update("UPDATE export_job SET status='RUNNING',lease_token='crashed',lease_expires_at=now()-interval '1 second' WHERE id=?",id);
        worker.processClaim(id);assertThat(delivery.getExportJob(id).getStatus().name()).isEqualTo("COMPLETED");
        long second=job(Map.of());
        doAnswer(call->{jdbc.update("UPDATE export_job SET lease_token='new-owner',lease_expires_at=now()+interval '2 minutes' WHERE id=?",second);return null;}).when(storage).putObject(anyString(),any());
        worker.processClaim(second);
        assertThat(delivery.getExportJob(second).getStatus().name()).isEqualTo("RUNNING");assertThat(delivery.getExportJob(second).getFileAssetId()).isNull();
    }
    @Test void rowLimitFailsExplicitlyWithoutPublishingTruncatedData() {
        jdbc.execute("INSERT INTO design_point_ledger(id,user_id,type,delta,available_after,reserved_after) SELECT n,7,'MANUAL_CREDIT',1,1,0 FROM generate_series(1,10001) n");
        long id=job(Map.of());worker.processClaim(id);
        assertThat(delivery.getExportJob(id).getStatus().name()).isEqualTo("FAILED");assertThat(delivery.getExportJob(id).getError()).isEqualTo("EXPORT_ROW_LIMIT");assertThat(objects).isEmpty();
    }
    @Test void invalidFilterDoesNotSilentlyBecomeFullExportAndOtherJobsStillRun() {
        ledger(1,7,"row");long bad=job(Map.of("unsupported","7"));long good=job(Map.of("userId","7"));
        ReflectionTestUtils.setField(worker,"enabled",true);worker.tick();
        assertThat(delivery.getExportJob(bad).getStatus().name()).isEqualTo("FAILED");
        assertThat(delivery.getExportJob(good).getStatus().name()).isEqualTo("COMPLETED");
        assertThatThrownBy(()->ExportFilters.validate(Map.of())).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->ExportFilters.validate(Map.of("jobType","AUDIT_EVENTS","userId","7"))).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
    }
    @Test void csvEscapesFormulaWhitespaceQuotesAndKeepsNumericDebits() {
        var out=new java.io.ByteArrayOutputStream();ZhongshuExportWorker.appendRow(out,"  =1","\t@SUM(1)","x,\"y\"",-5);
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("\"'  =1\",\"'\t@SUM(1)\",\"x,\"\"y\"\"\",\"-5\"\r\n");
        assertThatThrownBy(()->ZhongshuExportWorker.appendRow(out,"x".repeat(10*1024*1024))).hasMessage("EXPORT_SIZE_LIMIT");
    }
    @Test void duplicateOutboxDeliveryRemainsOneMessageEvenAfterReadReceipt() throws Exception {
        var event=new OutboxEventRecord(1001,"ORDER_CREDITED","recharge_order","10","{\"userId\":7}","worker");
        var pool=Executors.newFixedThreadPool(4);
        try {var tasks=new ArrayList<Future<?>>();for(int i=0;i<8;i++)tasks.add(pool.submit(()->messages.deliver(event)));for(var task:tasks)task.get(10,TimeUnit.SECONDS);}finally{pool.shutdownNow();}
        assertThat(messages.unreadCount(7)).isEqualTo(1);
        long message=((Number)messages.list(7,10).get(0).get("id")).longValue();messages.markRead(7,message);messages.deliver(event);
        assertThat(messages.unreadCount(7)).isZero();assertThat(messages.list(7,10)).hasSize(1);
    }
    @Test void historicalRefundWithoutRecipientResolvesOriginalOrderAndOwner() {
        jdbc.execute("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points) VALUES(10,'R10',7,1,'{}',1000,10,0)");
        // The separate RefundMigrationRecoveryTest upgrades actual old nullable rows. Here the
        // migrated shape retains its merchant number while the historical event has no recipient.
        jdbc.execute("INSERT INTO refund_order(id,order_id,refund_request_key,amount_cents,operator_id,channel_refund_id) VALUES(20,10,'refund20',1000,'admin','refund-20')");
        var event=new OutboxEventRecord(1002,"ORDER_REFUND_REVERSED","refund_order","20","{\"refundId\":20,\"orderId\":10}","worker");
        messages.deliver(event);messages.deliver(event);
        var message=messages.list(7,10).get(0);assertThat(message.get("biz_type")).isEqualTo("recharge_order");assertThat(message.get("biz_id")).isEqualTo("10");assertThat(message.get("title")).isEqualTo("退款已完成");assertThat(messages.list(8,10)).isEmpty();
        jdbc.execute("INSERT INTO case_submission(id,project_id,user_id,result_version_id) VALUES(30,1,7,1)");
        messages.deliver(new OutboxEventRecord(1004,"SUBMISSION_PUBLISHED","case_submission","30","{\"caseId\":50}","worker"));
        assertThat(messages.list(7,10).get(0).get("biz_type")).isEqualTo("case_submission");
    }
    @Test void invalidRecipientDoesNotBecomeUserZero() {
        assertThatThrownBy(()->messages.deliver(new OutboxEventRecord(1003,"ORDER_CREDITED","recharge_order","10","{\"userId\":\"not-an-id\"}","worker"))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_message",Integer.class)).isZero();
    }
}
