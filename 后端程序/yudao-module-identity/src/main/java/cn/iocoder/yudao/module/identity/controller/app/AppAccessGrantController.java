package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessGrantRespVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 访问授权")
@RestController
@RequestMapping("/design/v1/access-grant")
public class AppAccessGrantController {

    @Resource
    private UserSessionService sessionService;

    @Resource
    private cn.iocoder.yudao.module.identity.accesscode.AccessGrantService accessGrantService;

    @GetMapping
    @PermitAll // 自有 user_session Bearer 校验在方法内强制执行
    @Operation(summary = "查询当前账号的小程序使用权状态（授权撤销实时生效）")
    public CommonResult<AppAccessGrantRespVO> getAccessGrant(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        UserSessionService.AccessContext context = sessionService.validateAccessToken(token)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("会话无效或已过期"));

        AppAccessGrantRespVO vo = new AppAccessGrantRespVO();
        vo.setStatus(context.restricted() ? "NONE" : "ACTIVE");
        accessGrantService.findActiveByAccount(context.accountId()).ifPresent(grant ->
                vo.setGrantedAt(LocalDateTime.ofInstant(grant.grantedAt(), ZoneOffset.UTC)));
        vo.setAllowedActions(context.restricted()
                ? List.of("REDEEM_ACCESS_CODE", "VIEW_STATUS")
                : List.of("USE_PRODUCT", "VIEW_STATUS"));
        return success(vo);
    }

}
