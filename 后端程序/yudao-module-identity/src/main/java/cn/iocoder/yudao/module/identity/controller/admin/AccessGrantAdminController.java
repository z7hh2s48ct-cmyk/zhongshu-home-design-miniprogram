package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.accesscode.AccessGrantService;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 访问授权管理")
@RestController
@RequestMapping("/design/v1/access-grants")
public class AccessGrantAdminController {

    @Resource
    private AccessGrantService accessGrantService;

    @PostMapping("/{grantId}/revocations")
    @Operation(summary = "撤销访问授权（解绑）：原授权码保持已消费永不复活；会话授权态实时降级")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<Boolean> revokeAccessGrant(@PathVariable("grantId") Long grantId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        return success(accessGrantService.revoke(grantId, operator));
    }

}
