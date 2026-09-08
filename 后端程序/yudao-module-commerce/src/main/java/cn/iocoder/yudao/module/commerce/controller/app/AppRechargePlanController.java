package cn.iocoder.yudao.module.commerce.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.commerce.controller.app.vo.AppRechargePlanRespVO;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 充值方案（页面 17）。
 * 方案是公开的运营配置，不含个人数据，因此不要求会话——未激活用户也能看到价格表。
 */
@Tag(name = "小程序 - 充值方案（页面 17）")
@RestController
@RequestMapping("/design/v1/recharge-plans")
@PermitAll
public class AppRechargePlanController {

    @Resource
    private RechargePaymentService rechargePaymentService;

    @GetMapping
    @Operation(summary = "已启用充值方案列表（基础点/赠送点/合计，推荐与排序）")
    public CommonResult<List<AppRechargePlanRespVO>> getRechargePlans() {
        return success(rechargePaymentService.listEnabledPlans().stream().map(row -> {
            AppRechargePlanRespVO vo = new AppRechargePlanRespVO();
            vo.setPlanId(String.valueOf(row.get("id")));
            vo.setName((String) row.get("name"));
            vo.setAmountCents(((Number) row.get("amount_cents")).longValue());
            vo.setBasePoints(((Number) row.get("base_points")).intValue());
            vo.setBonusPoints(((Number) row.get("bonus_points")).intValue());
            vo.setRecommended(Boolean.TRUE.equals(row.get("recommended")));
            vo.setSort(toInt(row.get("sort")));
            vo.setAllowedActions(List.of("CREATE_ORDER"));
            return vo;
        }).toList());
    }

    private Integer toInt(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

}
