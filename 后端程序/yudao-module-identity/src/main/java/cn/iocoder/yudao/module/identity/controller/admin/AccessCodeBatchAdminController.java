package cn.iocoder.yudao.module.identity.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeCipher;
import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchCreateReqVO;
import cn.iocoder.yudao.module.identity.controller.admin.vo.AdminAccessCodeBatchRespVO;
import cn.iocoder.yudao.module.identity.enums.DeliveryModeEnum;
import cn.iocoder.yudao.module.identity.enums.PermissionConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 授权码批次（页面 08 批量生成）")
@RestController
@RequestMapping("/design/v1/access-code-batches")
public class AccessCodeBatchAdminController {

    @Resource
    private AccessCodeService accessCodeService;

    @PostMapping
    @Operation(summary = "创建授权码批次；INLINE 在响应返回完整码，TICKET 走一次性交付票据")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<AdminAccessCodeBatchRespVO> createBatch(@Valid @RequestBody AdminAccessCodeBatchCreateReqVO reqVO) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        AccessCodeService.BatchCreateResult result = accessCodeService.createBatch(
                reqVO.getQuantity(), DeliveryModeEnum.of(reqVO.getDeliveryMode()).getMode(),
                reqVO.getValidityDays(), reqVO.getPurposeNote(), operator);
        AdminAccessCodeBatchRespVO vo = new AdminAccessCodeBatchRespVO();
        vo.setId(result.batchId());
        vo.setQuantity(reqVO.getQuantity());
        vo.setDeliveryMode(result.deliveryMode());
        vo.setExposedCount(result.oneTimeCodes().size());
        vo.setOneTimeCodes(result.oneTimeCodes());
        return success(vo);
    }

    @PostMapping("/{batchId}/delivery-tickets")
    @Operation(summary = "生成批次的一次性交付票据（TICKET 模式；已有明文交付即拒绝）")
    @Parameter(name = "batchId", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_EXPORT + "')")
    public CommonResult<Map<String, Object>> createDeliveryTicket(@PathVariable("batchId") Long batchId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        var ticket = accessCodeService.issueDeliveryTicket(batchId, operator);
        return success(Map.of(
                "ticket", ticket.getToken(),
                "expiresAt", LocalDateTime.ofInstant(ticket.getExpiresAt(), ZoneOffset.UTC).toString()));
    }

    @PostMapping("/{batchId}/delivery-exports")
    @Operation(summary = "凭一次性票据交付完整明文（先原子消费票据再输出；票据重放必失败）")
    @Parameter(name = "batchId", description = "批次编号", required = true)
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_EXPORT + "')")
    public CommonResult<Map<String, Object>> exportByTicket(@PathVariable("batchId") Long batchId,
                                                            @RequestBody Map<String, String> body) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        List<String> codes = accessCodeService.exportByTicket(batchId, body.get("ticket"), operator);
        return success(Map.of("codes", codes, "notice", "批次票据仅可消费一次；未使用单码可在授权码列表复制"));
    }

}
