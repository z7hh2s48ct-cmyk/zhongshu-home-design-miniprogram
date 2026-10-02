package cn.iocoder.yudao.server.dev;
import cn.iocoder.yudao.module.aiorchestration.api.AiJobPortAdapter;
import cn.iocoder.yudao.module.aiorchestration.job.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.commerce.pricing.*;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignJobIntegrityTest {
    PostgreSQLContainer<?> pg;SimpleDriverDataSource ds;JdbcTemplate jdbc;DesignProjectService projects;
    AiJobSettlementService settle;AiJobOrchestrationService orch;PointAccountService points;
    PricingPort.PriceConfirmation flatPrice,elevationPrice;long project;
    static final GenerationImageOptions LAND=GenerationImageOptions.normalize("FLAT","2K","LANDSCAPE");
    @BeforeAll void database() {
        pg=new PostgreSQLContainer<>("postgres:17-alpine");pg.start();ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(pg.getJdbcUrl());ds.setUsername(pg.getUsername());ds.setPassword(pg.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
        jdbc=new JdbcTemplate(ds);var tx=new DataSourceTransactionManager(ds);points=new PointAccountService(ds,tx);
        var ledger=new PointLedgerPortAdapter(points);var events=new JdbcReliableEventPort(ds);var rules=new PriceRuleService(ds);
        orch=new AiJobOrchestrationService(ds,tx,events,null,null);settle=new AiJobSettlementService(ds,tx,new PricingPortAdapter(rules),ledger,orch,events);
        projects=new DesignProjectService(ds,tx,new AiJobPortAdapter(settle,orch),null,null,null);
    }
    @AfterAll void stop(){pg.stop();}
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE design_project,design_project_request,design_requirement_snapshot,design_candidate,design_selection,design_result_version,design_revision_request,ai_job,ai_job_attempt,ai_job_result,ai_task_charge,generation_price_rule,design_point_account,design_point_ledger,outbox_event");
        var rules=new PriceRuleService(ds);flatPrice=new PricingPort.PriceConfirmation(String.valueOf(rules.createRule("FLAT",10,1,4,Instant.now().minusSeconds(1),null)),1);
        elevationPrice=new PricingPort.PriceConfirmation(String.valueOf(rules.createRule("ELEVATION",15,1,4,Instant.now().minusSeconds(1),null)),1);
        points.credit(7,"RECHARGE_BASE_CREDIT",1000,"fixture","7","credit7",null,"fixture");
        points.credit(8,"RECHARGE_BASE_CREDIT",1000,"fixture","8","credit8",null,"fixture");
        project=projects.createProject(7,"SELF_UPLOAD",null,null,Map.of("prompt","base"));
    }
    void flat(long id,long candidate,long asset,String orientation) {
        jdbc.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,accepted_count,input_snapshot,output_prefix) VALUES(?,7,?,'FLAT','SUCCEEDED',1,1,CAST(? AS jsonb),?)",id,String.valueOf(project),"{\"phase\":\"FLAT\",\"imageOptions\":{\"resolution\":\"2K\",\"orientation\":\""+orientation+"\"}}","ai-quarantine/"+id);
        candidate(candidate,project,id,asset);
    }
    void candidate(long id,long p,long job,long asset){jdbc.update("INSERT INTO design_candidate(id,project_id,job_id,slot_no,asset_id,ai_result_id) VALUES(?,?,?,1,?,?)",id,p,job,asset,id);}
    void terminal(long job){jdbc.update("UPDATE ai_job SET status='SUCCEEDED',accepted_count=1 WHERE id=?",job);}
    String snapshot(long job){return jdbc.queryForObject("SELECT input_snapshot::text FROM ai_job WHERE id=?",String.class,job);}
    void conflict(Runnable call){assertThatThrownBy(call::run).isInstanceOfSatisfying(cn.iocoder.yudao.framework.common.exception.ServiceException.class,e->assertThat(e.getCode()).isEqualTo(cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.IDEMPOTENCY_KEY_REUSED.getCode()));}

    @Test void orientationUsesSelectedOldFlatAndVersionUsesThatJobsConfigurationAndSource() throws Exception {
        flat(201,401,601,"PORTRAIT");projects.selectFlatCandidate(7,project,401);
        flat(202,402,602,"LANDSCAPE"); // Newer, deliberately not selected.
        long first=projects.createElevationJob(7,project,1,"e1",Map.of("styleCode","MODERN"),elevationPrice,LAND).jobId();
        int snapshots=jdbc.queryForObject("SELECT count(*) FROM design_requirement_snapshot",Integer.class);
        assertThat(projects.createElevationJob(7,project,1,"e1",Map.of("styleCode","MODERN"),elevationPrice,LAND).jobId()).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_requirement_snapshot",Integer.class)).isEqualTo(snapshots);
        conflict(()->projects.createElevationJob(7,project,1,"e1",Map.of("styleCode","VILLA"),elevationPrice,LAND));
        assertThat(snapshot(first)).contains("PORTRAIT").contains("\"candidateId\": \"401\"");
        terminal(first);candidate(501,project,first,701);
        projects.selectFlatCandidate(7,project,402);
        long second=projects.createElevationJob(7,project,1,"e2",Map.of("styleCode","VILLA"),elevationPrice,LAND).jobId();
        assertThat(snapshot(second)).contains("LANDSCAPE");terminal(second);candidate(502,project,second,702);
        var pool=Executors.newFixedThreadPool(4);Set<Long> versions=new HashSet<>();
        try {var calls=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++)calls.add(()->projects.selectElevationCandidate(7,project,501).versionId());
            for(var f:pool.invokeAll(calls))versions.add(f.get(20,TimeUnit.SECONDS));} finally {pool.shutdownNow();}
        assertThat(versions).hasSize(1);var version=projects.listResultVersions(7,project).get(0);
        assertThat(version.get("config_snapshot").toString()).contains("MODERN").contains("PORTRAIT").doesNotContain("VILLA");
        assertThat(((Number)version.get("selected_flat_asset_id")).longValue()).isEqualTo(601);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_result_version",Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_selection WHERE project_id=? AND stage='ELEVATION' AND active=TRUE",Integer.class,project)).isEqualTo(1);
        long revision=projects.createRevisionRequest(7,project,"regenerate",null,1,"revision",elevationPrice,null,null).newJobId();
        assertThat(snapshot(revision)).contains("MODERN").contains("PORTRAIT").contains("\"candidateId\": \"401\"").doesNotContain("VILLA");
        conflict(()->projects.createRevisionRequest(7,project,"changed reason",null,1,"revision",elevationPrice,null,null));
    }

    @Test void wrongProjectOwnerOrPhaseCannotPromoteSelectOrBypassIdempotentSelection() {
        flat(201,401,601,"PORTRAIT");flat(202,402,602,"LANDSCAPE");projects.selectFlatCandidate(7,project,401);
        assertThat(projects.promoteCandidates(7,project,201)).extracting(DesignProjectService.CandidateRow::jobId).containsExactly(201L);
        long other=projects.createProject(7,"SELF_UPLOAD",null,null,Map.of());
        long wrong=settle.createJobWithCharge(7,"FLAT",1,"other",String.valueOf(other),flatPrice);terminal(wrong);
        assertThatThrownBy(()->projects.promoteCandidates(7,project,wrong)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->projects.promoteCandidatesForSettledJob(7,project,wrong)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThatThrownBy(()->projects.selectElevationCandidate(7,project,401)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        candidate(499,project,wrong,699);
        assertThatThrownBy(()->projects.selectFlatCandidate(7,project,499)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        long foreign=settle.createJobWithCharge(8,"FLAT",1,"foreign",String.valueOf(project),flatPrice);terminal(foreign);
        jdbc.update("UPDATE design_candidate SET job_id=? WHERE id=401",foreign);
        assertThatThrownBy(()->projects.selectFlatCandidate(7,project,401)).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_selection WHERE project_id=? AND active=TRUE",Integer.class,project)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM asset",Integer.class)).isZero();
    }

    @Test void accountScopedJobAndDebitIdentityRejectsChangedIntentAndAllowsAnotherAccountSameKey() throws Exception {
        long first=projects.createFlatJob(7,project,1,"shared",flatPrice,LAND).jobId();
        assertThat(projects.createFlatJob(7,project,1,"shared",flatPrice,LAND).jobId()).isEqualTo(first);
        long otherProject=projects.createProject(7,"SELF_UPLOAD",null,null,Map.of());
        conflict(()->projects.createFlatJob(7,otherProject,1,"shared",flatPrice,LAND));
        conflict(()->projects.createFlatJob(7,project,2,"shared",flatPrice,LAND));
        conflict(()->projects.createFlatJob(7,project,1,"shared",flatPrice,GenerationImageOptions.normalize("FLAT","4K","LANDSCAPE")));
        conflict(()->projects.createFlatJob(7,project,1,"shared",flatPrice,GenerationImageOptions.normalize("FLAT","2K","PORTRAIT")));
        conflict(()->settle.createJobWithCharge(7,"ELEVATION",1,"shared",String.valueOf(project),elevationPrice));
        conflict(()->orch.createJob(7,"FLAT",2,"shared",String.valueOf(project)));
        long account8=projects.createProject(8,"SELF_UPLOAD",null,null,Map.of("prompt","base"));
        long other=projects.createFlatJob(8,account8,1,"shared",flatPrice,LAND).jobId();assertThat(other).isNotEqualTo(first);
        var pool=Executors.newFixedThreadPool(4);Set<Long> ids=new HashSet<>();
        try {var calls=new ArrayList<Callable<Long>>();for(int i=0;i<8;i++)calls.add(()->settle.createJobWithCharge(7,"FLAT",1,"concurrent",String.valueOf(otherProject),flatPrice));
            for(var f:pool.invokeAll(calls))ids.add(f.get(20,TimeUnit.SECONDS));} finally {pool.shutdownNow();}
        assertThat(ids).hasSize(1);assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(980);
        assertThat(points.findAccount(8).orElseThrow().availablePoints()).isEqualTo(990);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT idempotency_key) FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",Integer.class)).isEqualTo(3);
    }

    @Test void legacyElevationSnapshotResolvesHistoricalSourceAssetWithoutTodaysSelection() {
        flat(201,401,601,"PORTRAIT");projects.selectFlatCandidate(7,project,401);
        long first=projects.createElevationJob(7,project,1,"legacy",Map.of("styleCode","MODERN"),elevationPrice,LAND).jobId();terminal(first);candidate(501,project,first,701);
        jdbc.update("UPDATE ai_job SET input_snapshot=input_snapshot-'sourceFlat' WHERE id=?",first);
        flat(202,402,602,"LANDSCAPE");projects.selectFlatCandidate(7,project,402);
        projects.selectElevationCandidate(7,project,501);
        assertThat(((Number)projects.listResultVersions(7,project).get(0).get("selected_flat_asset_id")).longValue()).isEqualTo(601);
    }

    @Test void realPreScopeSchemaUpgradePreservesLegacyKeyAnd4kChargeThenAllowsOtherAccount() {
        try(var old=new PostgreSQLContainer<>("postgres:17-alpine")) {
            old.start();var source=new SimpleDriverDataSource();source.setDriverClass(org.postgresql.Driver.class);
            source.setUrl(old.getJdbcUrl());source.setUsername(old.getUsername());source.setPassword(old.getPassword());
            String[] locations={"classpath:db/migration/platform","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration"};
            Flyway.configure().dataSource(source).locations(locations).target("20261003.402").load().migrate();
            var db=new JdbcTemplate(source);
            db.update("INSERT INTO generation_price_rule(id,stage,resolution,unit_point_cost,min_count,max_count,effective_at) VALUES(910,'FLAT','4K',10,1,4,now()-interval '1 minute')");
            db.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,idempotency_key,output_prefix) VALUES(911,7,'501','FLAT','SUCCEEDED',1,'legacy-key','ai-quarantine/911')");
            db.update("INSERT INTO ai_task_charge(id,job_id,user_id,price_rule_id,price_rule_version,stage,unit_point_cost,requested_count,total_point_cost,ledger_id) VALUES(912,911,7,910,1,'FLAT',10,1,10,913)");
            Flyway.configure().dataSource(source).locations(locations).load().migrate();
            assertThat(db.queryForObject("SELECT request_resolution FROM ai_job WHERE id=911",String.class)).isEqualTo("4K");
            assertThat(db.queryForObject("SELECT idempotency_key FROM ai_job WHERE id=911",String.class)).isEqualTo("legacy-key");
            db.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,idempotency_key,output_prefix) VALUES(914,8,'502','FLAT','QUEUED',1,'legacy-key','ai-quarantine/914')");
            assertThatThrownBy(()->db.update("INSERT INTO ai_job(id,user_id,project_ref,phase,status,requested_count,idempotency_key,output_prefix) VALUES(915,7,'503','FLAT','QUEUED',1,'legacy-key','ai-quarantine/915')"))
                    .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
            Flyway.configure().dataSource(source).locations(locations).load().migrate();
            assertThat(db.queryForObject("SELECT count(*) FROM ai_job",Integer.class)).isEqualTo(2);
        }
    }
    @Test void unkeyedTasksHaveIndependentBoundChargesAndBlankKeysStayUnkeyed() {
        long first=settle.createJobWithCharge(7,"FLAT",1,null,String.valueOf(project),flatPrice);
        long second=settle.createJobWithCharge(7,"FLAT",1," ",String.valueOf(project),flatPrice);
        long third=settle.createJobWithCharge(7,"FLAT",1,"",String.valueOf(project),flatPrice);
        assertThat(Set.of(first,second,third)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job WHERE idempotency_key IS NULL",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge c JOIN design_point_ledger l ON l.id=c.ledger_id WHERE l.biz_type='ai_job' AND l.biz_id=c.job_id::text AND l.user_id=c.user_id AND l.delta=-c.total_point_cost",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM design_point_ledger WHERE type='FLAT_GENERATION_DEBIT'",String.class)).allMatch(k->k.startsWith("AI_JOB:GENERATED:7:"));
        long keyed=settle.createJobWithCharge(7,"FLAT",1,"ordinary-request",String.valueOf(project),flatPrice);
        assertThat(settle.createJobWithCharge(7,"FLAT",1,"ordinary-request",String.valueOf(project),flatPrice)).isEqualTo(keyed);
        assertThat(jdbc.queryForObject("SELECT idempotency_key FROM design_point_ledger WHERE biz_id=?",String.class,String.valueOf(keyed))).startsWith("AI_JOB:REQUEST:7:");
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(960);
    }

    @Test void unrelatedLedgerReceiptCannotBeAttachedToNewJob() {
        var events=new JdbcReliableEventPort(ds);
        PointLedgerPort wrong=org.mockito.Mockito.mock(PointLedgerPort.class);
        long receipt=jdbc.queryForObject("SELECT id FROM design_point_ledger WHERE idempotency_key='credit7'",Long.class);
        org.mockito.Mockito.when(wrong.debit(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.isNull(),org.mockito.ArgumentMatchers.isNull())).thenReturn(receipt);
        var service=new AiJobSettlementService(ds,new DataSourceTransactionManager(ds),new PricingPortAdapter(new PriceRuleService(ds)),wrong,orch,events);
        conflict(()->service.createJobWithCharge(7,"FLAT",1,"ordinary-request",String.valueOf(project),flatPrice));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_task_charge",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_job",Integer.class)).isZero();
        assertThat(points.findAccount(7).orElseThrow().availablePoints()).isEqualTo(1000);
    }

    @Test void revisionsKeepMissingVersionFieldsAbsentDespiteNewerValidInputsAndChangedFlat() {
        flat(201,401,601,"PORTRAIT");projects.selectFlatCandidate(7,project,401);
        long selected=projects.createElevationJob(7,project,1,"selected",Map.of("styleCode","MODERN"),elevationPrice,LAND).jobId();
        terminal(selected);candidate(501,project,selected,701);
        flat(202,402,602,"LANDSCAPE");projects.selectFlatCandidate(7,project,402);
        long newer=projects.createElevationJob(7,project,1,"newer",Map.of("styleCode","VILLA","color","RED","roofType","SLOPED"),elevationPrice,LAND).jobId();
        terminal(newer);candidate(502,project,newer,702);
        projects.selectElevationCandidate(7,project,501);
        String original=projects.listResultVersions(7,project).get(0).get("config_snapshot").toString();
        long unchanged=projects.createRevisionRequest(7,project,"unchanged",null,1,"r1",elevationPrice,null,null).newJobId();
        assertThat(snapshot(unchanged)).contains("MODERN","PORTRAIT","\"assetId\": \"601\"").doesNotContain("color","roofType","VILLA","\"assetId\": \"602\"");
        long edited=projects.createRevisionRequest(7,project,"edit style",Map.of("styleCode","RURAL"),1,"r2",elevationPrice,null,null).newJobId();
        assertThat(snapshot(edited)).contains("RURAL","PORTRAIT","\"assetId\": \"601\"").doesNotContain("color","roofType","VILLA","\"assetId\": \"602\"");
        long added=projects.createRevisionRequest(7,project,"add color",Map.of("color","BLUE"),1,"r3",elevationPrice,null,null).newJobId();
        assertThat(snapshot(added)).contains("BLUE","MODERN","PORTRAIT","\"assetId\": \"601\"").doesNotContain("roofType","RED","VILLA","\"assetId\": \"602\"");
        assertThat(projects.listResultVersions(7,project).get(0).get("config_snapshot").toString()).isEqualTo(original);
        assertThat(snapshot(selected)).doesNotContain("color","roofType");
    }

}
