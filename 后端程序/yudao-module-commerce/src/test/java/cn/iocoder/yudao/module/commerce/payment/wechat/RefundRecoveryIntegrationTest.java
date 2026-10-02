package cn.iocoder.yudao.module.commerce.payment.wechat;
import cn.iocoder.yudao.module.commerce.payment.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.result.*;
import org.junit.jupiter.api.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RefundRecoveryIntegrationTest {
    PostgreSQLContainer<?> container; SimpleDriverDataSource ds; JdbcTemplate jdbc;
    PointAccountService points; RechargePaymentService payment; DataSourceTransactionManager tx;
    StubPaymentPortAdapter stub; WxPayService sdk; WechatPaymentAdapter real;
    @BeforeAll void database() {
        ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
        if(Boolean.getBoolean("prelaunch.nativePg")) {
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/postgres");ds.setUsername("dev_fixture");
            var admin=new JdbcTemplate(ds);
            if(admin.queryForObject("SELECT count(*) FROM pg_database WHERE datname='prelaunch_refund_v2'",Integer.class)==0) admin.execute("CREATE DATABASE prelaunch_refund_v2");
            ds.setUrl("jdbc:postgresql://127.0.0.1:55445/prelaunch_refund_v2");
        } else {
            container=new PostgreSQLContainer<>("postgres:17-alpine");container.start();
            ds.setUrl(container.getJdbcUrl());ds.setUsername(container.getUsername());ds.setPassword(container.getPassword());
        }
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/commerce").load().migrate();
        jdbc=new JdbcTemplate(ds);tx=new DataSourceTransactionManager(ds);points=new PointAccountService(ds,tx);
    }
    @AfterAll void stop() {if(container!=null)container.stop();}
    void channel(PaymentPort port) {payment=new RechargePaymentService(ds,tx,port,new PointLedgerPortAdapter(points),points,new JdbcReliableEventPort(ds),new PaymentFactValidator(ds,port));}
    @BeforeEach void clean() {
        jdbc.execute("TRUNCATE recharge_plan,recharge_order,payment_notification_inbox,payment_transaction,recharge_credit,refund_order,design_point_account,design_point_ledger,outbox_event,payment_anomaly_audit");
        stub=new StubPaymentPortAdapter();channel(stub);sdk=mock(WxPayService.class);
        var props=new WechatPayProperties();props.setMerchantId("fixture-merchant");real=new WechatPaymentAdapter(props,"fixture-app",sdk);
    }
    RechargePaymentService.OrderSnapshot paid() {
        long plan=payment.createPlan("fixture",1000,100,20,false,0);
        var order=payment.createOrder(901,plan,"fixture-order","fixture-openid");
        payment.processPaymentFact(order.orderNo(),"fixture-event","fixture-txn",1000,Instant.now());payment.fulfillOrder(order.orderNo());return order;
    }
    WxPayException missing() {var e=new WxPayException("fixture");e.setErrCode("RESOURCE_NOT_EXISTS");e.setErrCodeDes("退款单不存在");return e;}
    WxPayRefundV3Result response(String status) {var r=new WxPayRefundV3Result();r.setStatus(status);return r;}
    WxPayRefundQueryV3Result query(String status) {var r=new WxPayRefundQueryV3Result();r.setStatus(status);return r;}
    void due(long id) {jdbc.update("UPDATE refund_order SET next_reconcile_at=now()-interval '1 second' WHERE id=?",id);}
    @Test void crashBeforeFirstSendRecoversAuthoritativeMissingWithStableMerchantId() throws Exception {
        var order=paid();long id=91001;new TransactionTemplate(tx).execute(status -> {
            points.reserve(901,120,"refund_order",String.valueOf(order.orderId()));
            jdbc.update("INSERT INTO refund_order(id,order_id,refund_request_key,amount_cents,channel_state,point_reversal_state,reserved_base,reserved_bonus,operator_id,reason,channel_refund_id) VALUES(?,?,?,1000,'CREATED','RESERVED',100,20,'fixture','fixture',?)",id,order.orderId(),"crash-key","refund-"+id);return null;});
        channel(real);when(sdk.refundQueryV3("refund-"+id)).thenThrow(missing()).thenReturn(query("SUCCESS"));
        when(sdk.refundV3(any())).thenAnswer(invocation -> {var req=invocation.<WxPayRefundV3Request>getArgument(0);assertThat(req.getOutRefundNo()).isEqualTo("refund-"+id);return response("PROCESSING");});
        assertThat(payment.recoverPendingRefunds()).isZero();assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(120);
        due(id);assertThat(payment.recoverPendingRefunds()).isEqualTo(1);verify(sdk,times(1)).refundV3(any());
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
    }
    @Test void connectionFailureUnknownCanRecoverMissingButTemporaryQueryNeverResends() throws Exception {
        var order=paid();channel(real);var error=new WxPayException("fixture transport");error.setErrCode("SYSTEM_ERROR");
        when(sdk.refundV3(any())).thenThrow(error).thenReturn(response("SUCCESS"));
        long id=payment.requestRefund(order.orderId(),"fixture","fixture","unknown-key");
        when(sdk.refundQueryV3("refund-"+id)).thenThrow(error).thenThrow(missing());
        assertThat(payment.recoverPendingRefunds()).isZero();verify(sdk,times(1)).refundV3(any());
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(120);
        due(id);assertThat(payment.recoverPendingRefunds()).isEqualTo(1);verify(sdk,times(2)).refundV3(any());
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT channel_refund_id) FROM refund_order",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentSameKeyCreatesOneReservationAndOneRefundIdentity() throws Exception {
        var order=paid();stub.scriptRefund(order.orderNo(),"PROCESSING");var pool=Executors.newFixedThreadPool(6);
        try {var results=pool.invokeAll(IntStream.range(0,12).<Callable<Long>>mapToObj(i -> () -> payment.requestRefund(order.orderId(),"fixture","fixture","concurrent-key")).toList());
            Set<Long> ids=new HashSet<>();for(var f:results)ids.add(f.get(10,TimeUnit.SECONDS));assertThat(ids).hasSize(1);
        } finally {pool.shutdownNow();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM refund_order",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(120);
    }
    byte[] notification(String event,String order,long id,String state) {
        return ("{\"eventId\":\""+event+"\",\"orderNo\":\""+order+"\",\"channelRefundId\":\"refund-"+id+"\",\"state\":\""+state+"\",\"refundAmountCents\":1000,\"refundedAtEpochSecond\":2}").getBytes(StandardCharsets.UTF_8);
    }
    @Test void lateAbnormalProcessingAndFailureCannotUndoSuccessOrDoubleReverse() {
        var order=paid();stub.scriptRefund(order.orderNo(),"PROCESSING");long id=payment.requestRefund(order.orderId(),"fixture","fixture","late-key");
        assertThat(payment.handleRefundNotification(Map.of(),notification("success",order.orderNo(),id,"SUCCEEDED"))).isEqualTo("REVERSED");
        for(String state:List.of("ABNORMAL","PROCESSING","UNKNOWN","FAILED","SUCCEEDED")) payment.handleRefundNotification(Map.of(),notification("late-"+state,order.orderNo(),id,state));
        assertThat(jdbc.queryForObject("SELECT point_reversal_state FROM refund_order WHERE id=?",String.class,id)).isEqualTo("REVERSED");
        assertThat(points.findAccount(901).orElseThrow().availablePoints()).isZero();assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",Integer.class)).isEqualTo(2);
    }
    @Test void repeatedPendingPollingNeverResetsAgeOrHidesAbnormalStuckAlert() {
        var order=paid();stub.scriptRefund(order.orderNo(),"ABNORMAL");long id=payment.requestRefund(order.orderId(),"fixture","fixture","stuck-key");
        jdbc.update("UPDATE refund_order SET create_time=now()-interval '60 minutes' WHERE id=?",id);
        for(int i=0;i<3;i++){due(id);payment.recoverPendingRefunds();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type='REFUND_STUCK'",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(120);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PROCESSING","ABNORMAL"})
    void periodicDriverAuditsNewRefundOnlyOnceAndLaterSettles(String state) {
        var driver = new RefundRecoveryJob(payment);
        org.springframework.test.util.ReflectionTestUtils.setField(driver,"enabled",true);
        driver.onStartup(); // No refund exists at application startup.
        var order=paid();stub.scriptRefund(order.orderNo(),state);
        long id=payment.requestRefund(order.orderId(),"fixture","fixture","after-startup");
        due(id);driver.tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type='REFUND_STUCK'",Integer.class)).isZero();
        jdbc.update("UPDATE refund_order SET create_time=now()-interval '60 minutes' WHERE id=?",id);
        for(int i=0;i<3;i++){due(id);driver.tick();}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_anomaly_audit WHERE anomaly_type='REFUND_STUCK'",Integer.class)).isEqualTo(1);
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isEqualTo(120);
        stub.scriptRefund(order.orderNo(),"SUCCEEDED");due(id);driver.tick();due(id);driver.tick();
        assertThat(points.findAccount(901).orElseThrow().reservedPoints()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type LIKE '%REVERSAL'",Integer.class)).isEqualTo(2);
    }
}
