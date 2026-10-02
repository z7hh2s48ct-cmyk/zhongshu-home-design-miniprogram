package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.commerce.payment.*;
import cn.iocoder.yudao.module.commerce.points.*;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.OutboxEventRecord;
import cn.iocoder.yudao.module.infra.zhongshu.event.JdbcReliableEventPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundMigrationRecoveryTest {
    @Test void preUpgradeNullableAndExistingMerchantRefundsRecoverAndHistoricalMessagesKeepTheirOwner() {
        try(var pg=new PostgreSQLContainer<>("postgres:17-alpine")) {
            pg.start();var ds=new SimpleDriverDataSource();ds.setDriverClass(org.postgresql.Driver.class);
            ds.setUrl(pg.getJdbcUrl());ds.setUsername(pg.getUsername());ds.setPassword(pg.getPassword());
            String[] locations={"classpath:db/migration/platform","classpath:db/migration/design","classpath:db/migration/commerce"};
            Flyway.configure().dataSource(ds).locations(locations).target("20260930.306").load().migrate();
            var jdbc=new JdbcTemplate(ds);
            for(int n=0;n<3;n++) {
                jdbc.update("INSERT INTO recharge_order(id,order_no,user_id,plan_id,plan_snapshot,amount_cents,base_points,bonus_points,payment_state,fulfillment_state) VALUES(?,?,?,1,'{}',1000,100,0,'SUCCEEDED','CREDITED')",10+n,"R"+(10+n),7+n);
                jdbc.update("INSERT INTO design_point_account(id,user_id,available_points,reserved_points) VALUES(?,?,0,100)",7+n,7+n);
                jdbc.update("INSERT INTO refund_order(id,order_id,refund_request_key,amount_cents,operator_id,channel_refund_id,channel_state,point_reversal_state,reserved_base) VALUES(?,?,?,1000,'fixture',?,?,'RESERVED',100)",20+n,10+n,"old-"+n,n==0?null:n==1?"historic-merchant-21":"",n==1?"UNKNOWN":"CREATED");
            }
            assertThat(jdbc.queryForObject("SELECT channel_refund_id FROM refund_order WHERE id=20",String.class)).isNull();
            Flyway.configure().dataSource(ds).locations(locations).load().migrate();
            assertThat(jdbc.queryForList("SELECT channel_refund_id FROM refund_order ORDER BY id",String.class)).containsExactly("refund-20","historic-merchant-21","refund-22");
            Flyway.configure().dataSource(ds).locations(locations).load().migrate();
            var channel=mock(PaymentPort.class);when(channel.channel()).thenReturn("STUB");when(channel.merchantId()).thenReturn("fixture");
            when(channel.queryRefund("R10","refund-20")).thenReturn(new PaymentPort.ChannelRefundResult("SUCCEEDED"));
            when(channel.queryRefund("R11","historic-merchant-21")).thenReturn(new PaymentPort.ChannelRefundResult("SUCCEEDED"));
            when(channel.queryRefund("R12","refund-22")).thenReturn(new PaymentPort.ChannelRefundResult("NOT_FOUND"),new PaymentPort.ChannelRefundResult("SUCCEEDED"));
            when(channel.requestRefund(anyString(),anyString(),anyLong())).thenReturn(new PaymentPort.ChannelRefundResult("PROCESSING"));
            var tx=new DataSourceTransactionManager(ds);var points=new PointAccountService(ds,tx);
            var service=new RechargePaymentService(ds,tx,channel,new PointLedgerPortAdapter(points),points,new JdbcReliableEventPort(ds),new PaymentFactValidator(ds,channel));
            assertThat(service.recoverPendingRefunds()).isEqualTo(2);
            var sent=org.mockito.ArgumentCaptor.forClass(String.class);verify(channel).requestRefund(eq("R12"),sent.capture(),eq(1000L));
            assertThat(sent.getValue()).isEqualTo("refund-22");
            jdbc.update("UPDATE refund_order SET next_reconcile_at=now() WHERE id=22");
            assertThat(service.recoverPendingRefunds()).isEqualTo(1);assertThat(service.recoverPendingRefunds()).isZero();
            verify(channel,times(1)).requestRefund(anyString(),anyString(),anyLong());
            assertThat(jdbc.queryForObject("SELECT sum(reserved_points) FROM design_point_account",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM design_point_ledger WHERE type='RECHARGE_BASE_REVERSAL'",Integer.class)).isEqualTo(3);
            var messages=new MessageService(ds);var event=new OutboxEventRecord(1002,"ORDER_REFUND_REVERSED","refund_order","20","{\"refundId\":20,\"orderId\":10}","fixture");
            messages.deliver(event);messages.deliver(event);
            assertThat(messages.list(7,10)).hasSize(1);assertThat(messages.list(8,10)).isEmpty();
            assertThat(messages.list(7,10).get(0).get("biz_id")).isEqualTo("10");
        }
    }
}
