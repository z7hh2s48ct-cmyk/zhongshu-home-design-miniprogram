package cn.iocoder.yudao.server.runtime;

import cn.iocoder.yudao.module.aiorchestration.controller.internal.InternalSignatureVerifier;
import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RuntimeBoundaryTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:17-alpine");
    SimpleDriverDataSource ds; JdbcTemplate jdbc; InternalSignatureVerifier signer;
    RuntimeAssetController controller; ObjectStoragePort storage;
    @BeforeAll void migrate() {
        ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);ds.setUrl(PG.getJdbcUrl());ds.setUsername(PG.getUsername());ds.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE ai_job,ai_job_attempt,asset,asset_rights_grant,outbox_event,export_job");
        jdbc.execute("INSERT INTO ai_job(id,user_id,phase,status,requested_count,output_prefix,fencing_token,claim_expires_at,input_snapshot) VALUES(1,7,'FLAT','RUNNING',2,'ai-quarantine/1',3,now()+interval '1 minute','{\"schemaVersion\":1,\"phase\":\"FLAT\",\"assetIds\":[\"10\"],\"requirements\":{\"site\":\"frozen\"}}')");
        jdbc.execute("INSERT INTO ai_job_attempt(id,job_id,attempt_no,worker_id,fencing_token) VALUES(2,1,1,'runtime-test',3)");
        jdbc.execute("INSERT INTO asset(id,owner_user_id,object_key,asset_type,source_type,sha256,declared_mime,size_bytes,stored_sha256,stored_size,upload_status,security_scan_status,moderation_status) VALUES(10,7,'accepted/10/immutable','SKETCH','USER_UPLOAD',repeat('a',64),'image/png',100,repeat('b',64),100,'ACCEPTED','PASSED','PASSED')");
        signer=new InternalSignatureVerifier();ReflectionTestUtils.setField(signer,"sharedSecret","runtime-test-fixture");ReflectionTestUtils.setField(signer,"replayWindowSeconds",300L);
        storage=mock(ObjectStoragePort.class);when(storage.presignDownloadUrl(anyString(),eq(120L))).thenReturn("https://storage.example/read");when(storage.presignUploadUrl(anyString(),eq(120L))).thenReturn("https://storage.example/write");
        controller=new RuntimeAssetController(ds,storage,signer);
    }
    MockHttpServletRequest request(String suffix,String body) {
        var request=new MockHttpServletRequest("POST","/internal-api/design/v1/ai-jobs/1/"+suffix);
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);request.setContentType("application/json");request.setContent(bytes);
        String timestamp=String.valueOf(System.currentTimeMillis()/1000);
        request.addHeader("X-ZS-Timestamp",timestamp);request.addHeader("X-ZS-Signature",signer.sign(timestamp,"POST",request.getRequestURI(),bytes));return request;
    }
    Map<String,Object> inputs() throws Exception {return controller.inputs("1",new RuntimeAssetController.Lease(1,3),request("inputs","{\"attemptNo\":1,\"fencingToken\":3}")).getData();}
    @Test void validLeaseReceivesOnlyFrozenInputAndSeparateShortLivedOutputKey() throws Exception {
        var data=inputs();assertThat(data.get("requirements")).isEqualTo(Map.of("site","frozen"));
        assertThat((List<?>)data.get("images")).hasSize(1);
        verify(storage).presignDownloadUrl("accepted/10/immutable",120);
        var upload=controller.upload("1",new RuntimeAssetController.Upload(1,3,2,"image/png"),request("output-tickets","{}"));
        assertThat(upload.getData().get("objectKey").toString()).startsWith("ai-quarantine/1/3/2/").endsWith(".png");
        assertThat(upload.getData().get("maxBytes")).isEqualTo(7*1024*1024);
    }
    @Test void badSignatureExpiredLeaseOldFenceAndFinishedAttemptCannotGetTickets() {
        var bad=request("inputs","{}");bad.removeHeader("X-ZS-Signature");bad.addHeader("X-ZS-Signature","invalid");
        assertThatThrownBy(()->controller.inputs("1",new RuntimeAssetController.Lease(1,3),bad)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->controller.inputs("1",new RuntimeAssetController.Lease(1,2),request("inputs","{}"))).hasMessage("RUNTIME_LEASE");
        jdbc.execute("UPDATE ai_job SET claim_expires_at=now()-interval '1 second'");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_LEASE");
        jdbc.execute("UPDATE ai_job SET claim_expires_at=now()+interval '1 minute'");jdbc.execute("UPDATE ai_job_attempt SET finished_at=now()");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_LEASE");
        verifyNoInteractions(storage);
    }
    @Test void foreignOrWithdrawnReferenceAndUnreviewedAssetNeverReceiveReadUrl() throws Exception {
        jdbc.execute("UPDATE asset SET owner_user_id=8");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_INPUT_RIGHTS");
        jdbc.execute("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) VALUES(11,10,8,'fixture','PUBLIC_DISPLAY',now())");
        assertThatThrownBy(this::inputs).hasMessage("RUNTIME_INPUT_RIGHTS");
        jdbc.execute("UPDATE asset_rights_grant SET scope='GENERATION_REFERENCE'");assertThat((List<?>)inputs().get("images")).hasSize(1);
        jdbc.execute("UPDATE asset_rights_grant SET status='WITHDRAWN'");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_INPUT_RIGHTS");
        jdbc.execute("UPDATE asset SET owner_user_id=7,moderation_status='PENDING'");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_INPUT_RIGHTS");
        verify(storage,times(1)).presignDownloadUrl(anyString(),anyLong());
    }
    @Test void invalidSlotAndMissingSnapshotFailClosed() {
        assertThatThrownBy(()->controller.upload("1",new RuntimeAssetController.Upload(1,3,3,"image/png"),request("output-tickets","{}"))).hasMessage("RUNTIME_SLOT");
        jdbc.execute("UPDATE ai_job SET input_snapshot=NULL");assertThatThrownBy(this::inputs).hasMessage("RUNTIME_INPUT_SNAPSHOT_MISSING");verifyNoInteractions(storage);
    }
    @Test void httpBindingAndRepeatedBodyReadPreserveExactSignedJson() throws Exception {
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new cn.iocoder.yudao.framework.web.core.filter.CacheRequestBodyFilter()).build();
        String body="{\"attemptNo\":1,\"fencingToken\":3}";
        var signed=request("inputs",body);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(signed.getRequestURI())
                .contentType("application/json").content(body).header("X-ZS-Timestamp",signed.getHeader("X-ZS-Timestamp"))
                .header("X-ZS-Signature",signed.getHeader("X-ZS-Signature")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.requirements.site").value("frozen"));
    }
    @Test void backlogHealthDetectsQueuedDeadlineAndDatabaseFailureWithoutExceptionDetails() {
        var health=new BusinessBacklogHealth(ds);assertThat(health.health().getStatus().getCode()).isEqualTo("UP");
        jdbc.execute("UPDATE ai_job SET status='QUEUED',create_time=now()-interval '40 minutes'");
        assertThat(health.health().getStatus().getCode()).isEqualTo("DOWN");assertThat(health.health().getDetails()).containsEntry("aiOverdue",1L);
        var broken=new BusinessBacklogHealth(mock(javax.sql.DataSource.class));
        assertThat(broken.health().getDetails()).containsOnlyKeys("reason").containsEntry("reason","BACKLOG_QUERY_FAILED");
    }
}
