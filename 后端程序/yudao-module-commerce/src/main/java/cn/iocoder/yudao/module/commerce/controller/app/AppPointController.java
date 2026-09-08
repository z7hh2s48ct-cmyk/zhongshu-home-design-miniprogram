package cn.iocoder.yudao.module.commerce.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.commerce.controller.app.vo.AppPointAccountRespVO;
import cn.iocoder.yudao.module.commerce.controller.app.vo.AppPointLedgerItemRespVO;
import cn.iocoder.yudao.module.commerce.points.PointAccountService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 设计点（页面 15）。
 *
 * 小程序用户走专项自建会话（user_session + Bearer token），不进底座的 SecurityContext，
 * 因此必须用 IdentitySessionPort 解析身份——底座的 SecurityFrameworkUtils.getLoginUserId()
 * 对专项 token 恒返回 null，会在拆箱处直接 NPE。后台控制器才用底座上下文。
 */
@Tag(name = "小程序 - 设计点（页面 15）")
@RestController
@RequestMapping("/design/v1")
@PermitAll
public class AppPointController {

    @Resource
    private PointAccountService pointAccountService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping("/point-account")
    @Operation(summary = "我的点数账户（总余额 = available + reserved；服务端数据库是唯一真源）")
    public CommonResult<AppPointAccountRespVO> getPointAccount(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        PointAccountService.PointAccount account = pointAccountService.ensureAccount(userId);
        AppPointAccountRespVO vo = new AppPointAccountRespVO();
        vo.setAvailablePoints((int) account.availablePoints());
        vo.setReservedPoints((int) account.reservedPoints());
        vo.setVersion(account.version());
        vo.setAllowedActions(List.of("RECHARGE", "VIEW_LEDGER"));
        return success(vo);
    }

    @GetMapping("/point-ledger")
    @Operation(summary = "我的点数流水（只追加账本）")
    public CommonResult<PageResult<AppPointLedgerItemRespVO>> getPointLedger(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        PageResult<AppPointLedgerItemRespVO> result = new PageResult<>();
        result.setTotal(pointAccountService.countLedger(userId, null));
        result.setList(pointAccountService.pageLedger(userId, null, pageNo, pageSize).stream().map(row -> {
            AppPointLedgerItemRespVO vo = new AppPointLedgerItemRespVO();
            vo.setLedgerId(String.valueOf(row.id()));
            vo.setType(row.type());
            vo.setDelta((int) row.delta());
            vo.setBalanceAfter((int) (row.availableAfter() + row.reservedAfter()));
            vo.setBizType(row.bizType());
            vo.setBizId(row.bizId());
            vo.setCreatedAt(java.time.LocalDateTime.ofInstant(row.createTime(), java.time.ZoneOffset.UTC));
            return vo;
        }).toList());
        return success(result);
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

}
