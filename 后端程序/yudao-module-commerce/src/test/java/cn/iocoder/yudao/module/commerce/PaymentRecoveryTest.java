package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.module.commerce.payment.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PaymentRecoveryTest {
    @Container static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    SimpleDriverDataSource ds; JdbcTemplate jdbc; PointAccountService points; RechargePaymentService payment;
    Channel channel; long plan;
    static class Channel extends StubPaymentPortAdapter {
        final AtomicInteger calls = new AtomicInteger();
        boolean failFirst; boolean alreadyPaid; String poison;
        CountDownLatch entered, release;
        @Override public PrepayResult createPrepay(String n,long amount,String description,String openid) {
            int call=calls.incrementAndGet();
            if (failFirst && call==1) throw new IllegalStateException("simulated prepay timeout");
            if (entered != null) {entered.countDown();try { if(!release.await(10,TimeUnit.SECONDS))throw new IllegalStateException("test timeout"); } catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);} }
            if (alreadyPaid) return new PrepayResult("txn-"+n, Map.of("state","ALREADY_PAID"));
            return super.createPrepay(n,amount,description,openid);
        }
        @Override public ChannelQueryResult queryOrder(String n) {
            if (n.equals(poison)) throw new IllegalStateException("simulated query failure");
            return super.queryOrder(n);
        }
    }
    @BeforeAll void setup() {
        ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);ds.setUrl(PG.getJdbcUrl());ds.setUsername(PG.getUsername());ds.setPassword(PG.getPassword());
        jdbc=new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform","classpath:db/migration/commerce").load().migrate();
    }
    @BeforeEach void reset() {
        jdbc.execute("TRUNCATE recharge_plan,recharge_order,payment_notification_inbox,payment_transaction,recharge_credit,refund_order,design_point_account,design_point_ledger,outbox_event,payment_anomaly_audit");
        var tx=new DataSourceTransactionManager(ds);points=new PointAccountService(ds,tx);channel=new Channel();
        payment=new RechargePaymentService(ds,tx,channel,new PointLedgerPortAdapter(points),points,new JdbcReliableEventPort(ds),new PaymentFactValidator(ds,channel));
        plan=payment.createPlan("test plan",1000,100,20,false,0);
    }
    RechargePaymentService.OrderSnapshot create(long user,String key){return payment.createOrder(user,plan,key,"openid-"+user);}
    long balance(long user){return points.findAccount(user).map(PointAccountService.PointAccount::availablePoints).orElse(0L);}
    @Test void prepayTimeoutRetryRecoversTheSameOrder() {
        channel.failFirst=true;
        assertThatThrownBy(()->create(1,"same")).hasMessage("simulated prepay timeout");
        long id=jdbc.queryForObject("SELECT id FROM recharge_order",Long.class);
        var retry=create(1,"same");assertThat(retry.orderId()).isEqualTo(id);assertThat(retry.paymentState()).isEqualTo("PENDING");
        assertThat(channel.calls.get()).isEqualTo(2);assertThat(payment.getPayParams(1,id)).isPresent();
        assertThat(channel.calls.get()).isEqualTo(2); // reuse valid parameters without a second channel order call
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recharge_order",Integer.class)).isEqualTo(1);
    }
    @Test void recoveryAlsoHandlesCreatedOrdersWithLostPrepayResponse() {
        channel.failFirst=true;assertThatThrownBy(()->create(1,"lost")).isInstanceOf(IllegalStateException.class);
        String n=jdbc.queryForObject("SELECT order_no FROM recharge_order",String.class);channel.scriptQuery(n,"UNKNOWN");
        payment.recoverHangingOrders();assertThat(channel.calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payment_state FROM recharge_order",String.class)).isEqualTo("PENDING");
    }
    @Test void expiredParamsRefreshAndForeignUsersCannotTriggerPrepay() {
        var order=create(1,"expiry");jdbc.update("UPDATE recharge_order SET prepay_expires_at=now()-interval '1 second'");
        assertThat(payment.getPayParams(2,order.orderId(),"openid-2")).isEmpty();assertThat(channel.calls.get()).isEqualTo(1);
        assertThatThrownBy(()->payment.getPayParams(1,order.orderId(),"different-owner")).isInstanceOf(cn.iocoder.yudao.framework.common.exception.ServiceException.class);
        assertThat(payment.getPayParams(1,order.orderId(),"openid-1")).isPresent();assertThat(channel.calls.get()).isEqualTo(2);
    }
    @Test void alreadyPaidPrepayResponseReconcilesInsteadOfStoringMarkerParams() {
        var order=create(1,"paid");channel.alreadyPaid=true;channel.scriptQuery(order.orderNo(),"SUCCEEDED");channel.scriptAmount(order.orderNo(),1000L);
        jdbc.update("UPDATE recharge_order SET prepay_expires_at=now()-interval '1 second'");
        assertThat(payment.getPayParams(1,order.orderId())).isEmpty();assertThat(balance(1)).isEqualTo(120);
        assertThat(payment.getOrderById(order.orderId()).orElseThrow().paymentState()).isEqualTo("SUCCEEDED");
        payment.reconcile(order.orderNo());assertThat(balance(1)).isEqualTo(120);
    }
    @Test void oldClosedOrdersCannotStarveNewerPaidOrder() {
        for(int i=0;i<50;i++){var old=create(1,"old"+i);channel.scriptQuery(old.orderNo(),"CLOSED");}
        var paid=create(2,"new");channel.scriptAmount(paid.orderNo(),1000L);
        payment.recoverHangingOrders();payment.recoverHangingOrders();
        assertThat(balance(2)).isEqualTo(120);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recharge_order WHERE payment_state='CLOSED'",Integer.class)).isEqualTo(50);
    }
    @Test void pendingOrdersAreBackedOffFairlyAndDoNotStarveNewerOrders() {
        for(int i=0;i<50;i++){var old=create(1,"pending"+i);channel.scriptQuery(old.orderNo(),"PENDING");}
        var paid=create(2,"new");channel.scriptAmount(paid.orderNo(),1000L);
        payment.recoverHangingOrders();payment.recoverHangingOrders();assertThat(balance(2)).isEqualTo(120);
    }
    @Test void poisonOrderDoesNotSkipOtherOrdersAndPaidFactResumesFulfillment() {
        var bad=create(1,"bad");channel.poison=bad.orderNo();var paid=create(2,"paid");
        payment.processPaymentFact(paid.orderNo(),"event","tx-verified",1000,java.time.Instant.now());
        payment.recoverHangingOrders();assertThat(balance(2)).isEqualTo(120);assertThat(balance(1)).isZero();
    }
    @Test void concurrentIdempotentCreateHasOneOrderAndOnePrepayLease() throws Exception {
        channel.entered=new CountDownLatch(1);channel.release=new CountDownLatch(1);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            var first=pool.submit(()->create(1,"race"));assertThat(channel.entered.await(5,TimeUnit.SECONDS)).isTrue();
            var second=pool.submit(()->create(1,"race")).get(5,TimeUnit.SECONDS);
            channel.release.countDown();var result=first.get(5,TimeUnit.SECONDS);
            assertThat(result.orderId()).isEqualTo(second.orderId());assertThat(channel.calls.get()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM recharge_order",Integer.class)).isEqualTo(1);
        } finally {channel.release.countDown();pool.shutdownNow();}
    }
}
