package cn.iocoder.yudao.module.aiorchestration;
import cn.iocoder.yudao.module.aiorchestration.job.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.*;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptReservationRecoveryIntegrationTest {
    PostgreSQLContainer<?> container;SimpleDriverDataSource ds;JdbcTemplate jdbc;PointAccountService points;
    UsagePointPriceService usage;AiJobOrchestrationService orch;AiJobSettlementService service;PricingPort.PriceConfirmation price;
    @BeforeAll void database() {
        ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
        if(Boolean.getBoolean("prelaunch.nativePg")) {
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/postgres");ds.setUsername("dev_fixture");var admin=new JdbcTemplate(ds);
            if(admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='prelaunch_prompt'",Integer.class)==0)admin.execute("CREATE DATABASE prelaunch_prompt");
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/prelaunch_prompt");
        } else {container=new PostgreSQLContainer<>("postgres:17-alpine");container.start();ds.setUrl(container.getJdbcUrl());ds.setUsername(container.getUsername());ds.setPassword(container.getPassword());}
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
        jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);points=new PointAccountService(ds,tx);var ledger=new PointLedgerPortAdapter(points);
        var events=new JdbcReliableEventPort(ds);orch=new AiJobOrchestrationService(ds,tx,events,null,null);
        var rules=new PriceRuleService(ds);usage=new UsagePointPriceService(ds,tx,ledger);
        service=new AiJobSettlementService(ds,tx,new PricingPortAdapter(rules),ledger,orch,events,usage);
    }
    @AfterAll void stop(){if(container!=null)container.stop();}
    @BeforeEach void clean(){
        jdbc.execute("TRUNCATE ai_job,ai_job_attempt,ai_job_result,ai_job_settlement,ai_task_charge,service_usage_charge,service_usage_price_rule,generation_price_rule,design_point_account,design_point_ledger,outbox_event");
        var rules=new PriceRuleService(ds);long id=rules.createRule("FLAT",10,1,4,Instant.now().minusSeconds(2),null);
        usage.createRule("AI_PROMPT",7,Instant.now().minusSeconds(1),null,"fixture");var quote=usage.quote("AI_PROMPT");
        price=new PricingPort.PriceConfirmation(String.valueOf(id),1,new UsagePricingPort.Confirmation("AI_PROMPT",String.valueOf(quote.ruleId()),quote.ruleVersion()));
        points.credit(901,"RECHARGE_BASE_CREDIT",1000,"fixture","fixture","fixture-credit",null,"fixture");
    }
    long job(){return service.createJobWithCharge(901,"FLAT",1,"fixture-job","91",price);}
    @Test void changedOrRetiredUsagePriceReturnsDefinitePriceRejectionBeforeAnyCharge() {
        var before=usage.quote("AI_PROMPT");
        usage.retireRule(before.ruleId(),"fixture");
        assertThatThrownBy(()->usage.prepareCharge(901,before,"ai_job","changed"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class)
                .satisfies(error->assertThat(((cn.iocoder.yudao.framework.common.exception.ServiceException)error).getCode()).isEqualTo(1072000001));
        usage.createRule("AI_PROMPT",9,Instant.now().minusSeconds(1),null,"fixture");
        assertThatThrownBy(()->usage.prepareCharge(901,before,"ai_job","changed"))
                .isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM service_usage_charge",Integer.class)).isZero();
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(1000);
    }
    @Test void receivedReceiptTransfersConfirmationToCurrentAttemptButRejectsStaleHolder() {
        long id=job();var first=orch.claim("fixture-first",1,60,"fake").get(0);String call="plan-"+id;String hash="a".repeat(64);
        service.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        service.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"DISPATCHED",call,null);
        jdbc.update("UPDATE ai_job SET claim_expires_at=now()-interval '1 second' WHERE id=?",id);
        var next=orch.claim("fixture-next",1,60,"fake").get(0);
        assertThatThrownBy(()->service.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"SUCCEEDED",call,hash)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service.reservePromptCall(id,next.attemptNo(),next.fencingToken())).isInstanceOf(IllegalStateException.class);
        assertThat(service.finishPromptCall(id,next.attemptNo(),next.fencingToken(),"SUCCEEDED",call,hash)).isTrue();
        assertThat(service.finishPromptCall(id,next.attemptNo(),next.fencingToken(),"SUCCEEDED",call,hash)).isFalse();
        assertThatThrownBy(()->service.finishPromptCall(id,next.attemptNo(),next.fencingToken(),"SUCCEEDED",call,"b".repeat(64))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='AI_PROMPT_DEBIT'",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(983);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
    }
    @Test void knownUnsentReleasesAndUnknownStaysHeldUntilEvidenceReconciliation() {
        long id=job();var first=orch.claim("fixture",1,60,"fake").get(0);String call="plan-"+id;
        service.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        service.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"NOT_SENT",call,null);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
        service.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        service.finishPromptCall(id,first.attemptNo(),first.fencingToken(),"DISPATCHED",call,null);
        service.settle(id,"SETTLE");assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(7);
        assertThat(usage.reconcilePrompt(String.valueOf(id),true)).isTrue();assertThat(usage.reconcilePrompt(String.valueOf(id),true)).isFalse();
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(993);
    }
    @Test void reservationBeforeDispatchCrashIsReleasedAndLegacyTaskIsExempt() {
        long id=job();var first=orch.claim("fixture",1,60,"fake").get(0);service.reservePromptCall(id,first.attemptNo(),first.fencingToken());
        service.settle(id,"SETTLE");assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(1000);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
        var oldPrice=new PricingPort.PriceConfirmation(price.ruleId(),price.ruleVersion());
        var legacy=new AiJobSettlementService(ds,new DataSourceTransactionManager(ds),new PricingPortAdapter(new PriceRuleService(ds)),new PointLedgerPortAdapter(points),orch,new JdbcReliableEventPort(ds));
        long old=legacy.createJobWithCharge(901,"FLAT",1,"legacy","91",oldPrice);var claim=orch.claim("fixture",1,60,"fake").get(0);
        assertThat(service.reservePromptCall(old,claim.attemptNo(),claim.fencingToken())).isEqualTo("EXEMPT");
        service.settle(old,"SETTLE");assertThat(points.findAccount(901).orElseThrow().availablePoints()).isEqualTo(1000);
    }
}
