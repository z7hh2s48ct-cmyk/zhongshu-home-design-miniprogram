package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppPrivacyConsentRespVO;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 协议与同意")
@RestController
@RequestMapping("/design/v1/privacy-consents")
@PermitAll
public class AppPrivacyController {

    @Resource
    private PrivacyService privacyService;

    @Resource
    private UserSessionService sessionService;

    @GetMapping
    @Operation(summary = "查询当前协议版本与已同意事实（受限会话可查，便于激活前告知）")
    public CommonResult<List<AppPrivacyConsentRespVO>> getConsents(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(privacyService.listConsents(userId).stream().map(row -> {
            AppPrivacyConsentRespVO vo = new AppPrivacyConsentRespVO();
            vo.setPolicyType(row.policyType());
            vo.setVersion(row.version());
            vo.setAcceptedAt(row.acceptedAt() == null ? null
                    : LocalDateTime.ofInstant(row.acceptedAt(), ZoneId.systemDefault()));
            return vo;
        }).toList());
    }

    @PostMapping
    @Operation(summary = "记录当前版本三类协议的同意事实（同版本重复同意幂等）")
    public CommonResult<Boolean> acceptConsents(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        privacyService.acceptCurrentConsents(requireAccountId(authorization));
        return success(true);
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new AccessDeniedException("会话无效或已过期"))
                .accountId();
    }

}
