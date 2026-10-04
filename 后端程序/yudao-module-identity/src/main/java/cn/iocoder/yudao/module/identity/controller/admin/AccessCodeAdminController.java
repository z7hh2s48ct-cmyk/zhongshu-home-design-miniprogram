package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeRespVO;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 授权码查询与停用（页面 07）")
@RestController
@RequestMapping("/design/v1/access-codes")
public class AccessCodeAdminController {

    @Resource
    private AccessCodeService accessCodeService;

    @GetMapping
    @Operation(summary = "分页查询授权码（列表与搜索永远只显示掩码）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<PageResult<AdminAccessCodeRespVO>> getAccessCodePage(
            @RequestParam(value = "batchId", required = false) Long batchId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "codeMask", required = false) String codeMask,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AdminAccessCodeRespVO> result = new PageResult<>();
        result.setTotal(accessCodeService.countCodes(batchId, status, codeMask));
        var rows = accessCodeService.pageCodes(batchId, status, codeMask, pageNo, pageSize).stream().map(row -> {
            AdminAccessCodeRespVO vo = new AdminAccessCodeRespVO();
            vo.setId(String.valueOf(row.id()));
            vo.setCodeMask(row.codeMask());
            vo.setBatchId(String.valueOf(row.batchId()));
            vo.setStatus(row.status());
            vo.setCanCopy(row.canCopy());
            vo.setIssuedAt(LocalDateTime.ofInstant(row.issuedAt(), ZoneOffset.UTC));
            vo.setConsumedAt(row.consumedAt() == null ? null : LocalDateTime.ofInstant(row.consumedAt(), ZoneOffset.UTC));
            vo.setSecretExposedAt(row.secretExposedAt() == null ? null
                    : LocalDateTime.ofInstant(row.secretExposedAt(), ZoneOffset.UTC));
            vo.setExpiresAt(row.expiresAt() == null ? null
                    : LocalDateTime.ofInstant(row.expiresAt(), ZoneOffset.UTC));
            vo.setBoundUser(row.boundAccountId() == null ? null : String.valueOf(row.boundAccountId()));
            return vo;
        }).toList();
        result.setList(rows);
        return success(result);
    }

    @GetMapping("/stats")
    @Operation(summary = "授权码四态计数（UNUSED 未使用 / BOUND 已绑定 / EXPIRED 已过期 / DISABLED 已停用）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<Map<String, Long>> getStatusCounts() {
        return success(accessCodeService.statusCounts());
    }

    @PatchMapping("/{codeId}")
    @Operation(summary = "停用授权码（仅 ACTIVE 可停用；已消费码不可停用为可兑换）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<Boolean> disableAccessCode(@PathVariable("codeId") Long codeId,
                                                   @RequestBody Map<String, Object> action) {
        return success(accessCodeService.disableCode(codeId));
    }

    @PostMapping("/{codeId}/copy")
    @Operation(summary = "复制授权码明文（任意未删除状态均可，含已绑定/已停用/已过期）；记录操作日志")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_EXPORT + "')")
    public CommonResult<String> copyAccessCode(@PathVariable("codeId") Long codeId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        return success(accessCodeService.copyCodePlaintext(codeId, operator));
    }

    @DeleteMapping("/{codeId}")
    @Operation(summary = "逻辑删除未兑换授权码并停用；已兑换码不可删除")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<Boolean> deleteAccessCode(@PathVariable("codeId") Long codeId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        return success(accessCodeService.deleteUnusedCode(codeId, operator));
    }

}
