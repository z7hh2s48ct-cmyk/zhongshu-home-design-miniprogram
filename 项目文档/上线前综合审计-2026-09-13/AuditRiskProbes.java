import cn.iocoder.yudao.module.design.asset.*;
import cn.iocoder.yudao.module.design.rights.RightsGrantService;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
import cn.iocoder.yudao.module.commerce.payment.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.JdbcDeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.*;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.*;
import software.amazon.awssdk.services.s3.model.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Audit-only probes. Only fresh local Testcontainers; no live credentials or shared database. */
public class AuditRiskProbes {
  static class FlakyPrepay extends StubPaymentPortAdapter {
    int calls;
    @Override public PrepayResult createPrepay(String n,long a,String d,String o) {
      if (++calls == 1) throw new IllegalStateException("audit simulated prepay timeout");
      return super.createPrepay(n,a,d,o);
    }
  }
  static void require(boolean b,String m){if(!b)throw new AssertionError(m);}
  public static void main(String[] args) throws Exception {
    try (var pg = new PostgreSQLContainer<>("postgres:17-alpine").withDatabaseName("zs_audit").withUsername("audit").withPassword("audit-local-only");
         var minio = new GenericContainer<>("minio/minio:RELEASE.2024-01-16T16-07-38Z").withExposedPorts(9000)
           .withEnv("MINIO_ROOT_USER","auditlocal").withEnv("MINIO_ROOT_PASSWORD","audit-local-only-password")
           .withCommand("server","/data").waitingFor(Wait.forHttp("/minio/health/ready").forPort(9000).forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)))) {
      pg.start(); minio.start();
      var ds=new SimpleDriverDataSource(); ds.setDriverClass(org.postgresql.Driver.class);
      ds.setUrl(pg.getJdbcUrl()); ds.setUsername(pg.getUsername()); ds.setPassword(pg.getPassword());
      Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/identity","classpath:db/migration/design","classpath:db/migration/commerce","classpath:db/migration/ai-orchestration").load().migrate();
      var jdbc=new JdbcTemplate(ds); var tx=new DataSourceTransactionManager(ds);
      String endpoint="http://"+minio.getHost()+":"+minio.getMappedPort(9000), bucket="audit-private-bucket";
      try(var s3=S3Client.builder().endpointOverride(URI.create(endpoint)).region(Region.US_EAST_1)
          .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("auditlocal","audit-local-only-password")))
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build()).build()) {
        s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
      }
      var storage=new CosObjectStorageAdapter(endpoint,"us-east-1",bucket,"auditlocal","audit-local-only-password",true,20000);
      try {
        var assets=new AssetService(ds,tx,storage,new AssetContentScanner(),new StubContentModerationAdapter(),new JdbcDeliveryPort(ds),new RightsGrantService(ds));
        var png=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(32,32,BufferedImage.TYPE_INT_RGB),"png",png);
        byte[] clean=png.toByteArray();
        var ticket=assets.createUploadTicket(101,"USER_SKETCH","image/png",clean.length,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(clean)));
        var http=HttpClient.newHttpClient();
        require(put(http,ticket.uploadUrl(),clean)==200,"initial upload");
        require("ACCEPTED".equals(assets.completeUpload(101,ticket.assetId())),"asset initial scan");
        byte[] marker="AUDIT-HARMLESS-UNSCANNED-REPLACEMENT".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int replayStatus=put(http,ticket.uploadUrl(),marker);
        var download=assets.requestDownloadTicket(101,ticket.assetId());
        String url=assets.resolveDownloadTicket(101,ticket.assetId(),download.getToken());
        var read=http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        String state=jdbc.queryForObject("SELECT upload_status FROM asset WHERE id=?",String.class,ticket.assetId());
        boolean bypass=replayStatus==200 && Arrays.equals(marker,read.body()) && "ACCEPTED".equals(state);
        require(bypass,"overwrite probe expected current defect");
        System.out.println("AUDIT_RESULT COS_OVERWRITE reproduced=true replayHttp="+replayStatus+" assetState="+state+" unscannedBytesDownloadable=true");
      } finally {storage.close();}
      var points=new PointAccountService(ds,tx); var flaky=new FlakyPrepay();
      var payment=new RechargePaymentService(ds,tx,flaky,new PointLedgerPortAdapter(points),points,new JdbcReliableEventPort(ds),new PaymentFactValidator(ds,flaky));
      long plan=payment.createPlan("Audit only",1000,100,20,false,0);
      try {payment.createOrder(201,plan,"audit-prepay","audit-openid");throw new AssertionError("expected initial failure");}
      catch(IllegalStateException expected){require(expected.getMessage().equals("audit simulated prepay timeout"),"expected simulated failure");}
      var retry=payment.createOrder(201,plan,"audit-prepay","audit-openid"); payment.recoverHangingOrders();
      boolean stranded="CREATED".equals(retry.paymentState())&&flaky.calls==1&&payment.getPayParams(201,retry.orderId()).isEmpty();
      require(stranded,"prepay probe expected current defect");
      System.out.println("AUDIT_RESULT PREPAY_RECOVERY reproduced=true state="+retry.paymentState()+" channelCalls="+flaky.calls+" payParamsPresent=false");
      var stub=new StubPaymentPortAdapter();
      var p2=new RechargePaymentService(ds,tx,stub,new PointLedgerPortAdapter(points),points,new JdbcReliableEventPort(ds),new PaymentFactValidator(ds,stub));
      for(int i=0;i<50;i++){var old=p2.createOrder(202,plan,"audit-old-"+i,"audit-openid");stub.scriptQuery(old.orderNo(),"CLOSED");}
      var newer=p2.createOrder(203,plan,"audit-newer","audit-openid");stub.scriptQuery(newer.orderNo(),"SUCCEEDED");stub.scriptAmount(newer.orderNo(),1000L);
      p2.recoverHangingOrders();p2.recoverHangingOrders();
      var after=p2.getOrderById(newer.orderId()).orElseThrow();
      require("PENDING".equals(after.paymentState())&&"NOT_READY".equals(after.fulfillmentState()),"starvation expected current defect");
      System.out.println("AUDIT_RESULT PAYMENT_SCAN_STARVATION reproduced=true oldClosedRows=50 scanRounds=2 newerState="+after.paymentState()+" fulfillment="+after.fulfillmentState());
      p2.reconcile(newer.orderNo());
      var credited=jdbc.queryForMap("SELECT id,event_type,biz_type,biz_id,payload::text AS payload FROM outbox_event WHERE event_type='ORDER_CREDITED' AND biz_id=?",String.valueOf(newer.orderId()));
      var messages=new MessageService(ds);
      var event=new OutboxEventRecord(((Number)credited.get("id")).longValue(),(String)credited.get("event_type"),(String)credited.get("biz_type"),(String)credited.get("biz_id"),(String)credited.get("payload"),"audit");
      messages.deliver(event); messages.deliver(event);
      int messageCount=jdbc.queryForObject("SELECT count(*) FROM user_message WHERE user_id=203 AND message_type='ORDER_CREDITED'",Integer.class);
      require(messageCount==2,"duplicate event probe expected current defect");
      System.out.println("AUDIT_RESULT MESSAGE_REDELIVERY reproduced=true deliveries=2 userMessages="+messageCount);
      long refund=p2.requestRefund(newer.orderId(),"audit","audit-only refund","audit-refund");
      var refunded=jdbc.queryForMap("SELECT id,event_type,biz_type,biz_id,payload::text AS payload FROM outbox_event WHERE event_type='ORDER_REFUND_REVERSED' AND biz_id=?",String.valueOf(refund));
      var refundEvent=new OutboxEventRecord(((Number)refunded.get("id")).longValue(),(String)refunded.get("event_type"),(String)refunded.get("biz_type"),(String)refunded.get("biz_id"),(String)refunded.get("payload"),"audit");
      boolean refundRejected=false;
      try {messages.deliver(refundEvent);} catch(IllegalStateException expected){refundRejected=expected.getMessage().contains("userId");}
      String refundState=jdbc.queryForObject("SELECT point_reversal_state FROM refund_order WHERE id=?",String.class,refund);
      require(refundRejected&&"REVERSED".equals(refundState),"refund notification probe expected current defect");
      System.out.println("AUDIT_RESULT REFUND_NOTIFICATION reproduced=true reversalState="+refundState+" sinkRejectedMissingUserId=true");
      System.out.println("AUDIT_PROBES_COMPLETED count=5 environment=isolated-PostgreSQL17-MinIO noRealChannel=true");
    }
  }
  static int put(HttpClient http,String url,byte[] body) throws Exception {
    return http.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type","image/png").PUT(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofString()).statusCode();
  }
}
