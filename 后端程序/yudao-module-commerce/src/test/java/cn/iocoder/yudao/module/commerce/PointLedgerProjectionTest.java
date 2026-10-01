package cn.iocoder.yudao.module.commerce;

import cn.iocoder.yudao.module.commerce.controller.admin.PointAdminController;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PointLedgerProjectionTest {
    @Test void unfilteredLedgerUsesEachRowsOwnerAndPreservesLargeIdAsString() {
        var service=mock(PointAccountService.class);var controller=new PointAdminController();
        ReflectionTestUtils.setField(controller,"pointAccountService",service);
        when(service.countLedger(null,null)).thenReturn(2L);
        when(service.pageLedger(null,null,1,20)).thenReturn(List.of(
                new PointAccountService.LedgerRow(71,9007199254741001L,"MANUAL_CREDIT",10,10,0,"fixture","1","fixture","fixture",Instant.now()),
                new PointAccountService.LedgerRow(72,902,"MANUAL_CREDIT",20,20,0,"fixture","2","fixture","fixture",Instant.now())));
        var result=controller.getLedgerPage(null,null,1,20).getData();
        assertThat(result.getTotal()).isEqualTo(2);assertThat(result.getList()).extracting("userId")
                .containsExactly("9007199254741001","902");
    }
}
