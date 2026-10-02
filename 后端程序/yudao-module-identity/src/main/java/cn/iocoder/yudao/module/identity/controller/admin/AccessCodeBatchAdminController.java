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
import org.springframework.web.bind.annotation.GetMapping;
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

    @jakarta.annotation.Resource
    private javax.sql.DataSource dataSource;

    @GetMapping
    @Operation(summary = "批次分页（F-1 批次视图）：数量/已兑换/明文暴露/用途；兑换数按兑换事实统计")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.ACCESS_CODE_MANAGE + "')")
    public CommonResult<cn.iocoder.yudao.framework.common.pojo.PageResult<java.util.Map<String, Object>>> getBatchPage(
            @org.springframework.web.bind.annotation.RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @org.springframework.web.bind.annotation.RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        int size = Math.min(Math.max(pageSize, 1), 100);
        Long total = jdbc.queryForObject("SELECT count(*) FROM design_access_code_batch WHERE deleted = FALSE", Long.class);
        var rows = jdbc.queryForList(
                "SELECT b.id, b.quantity, b.delivery_mode, b.exposed_count, b.issued_by, b.purpose_note, "
                        + "b.expires_at, b.create_time, "
                        + "(SELECT count(*) FROM access_code_redemption r WHERE r.code_id IN "
                        + "  (SELECT c.id FROM design_access_code c WHERE c.batch_id = b.id)) AS redeemed_count "
                        + "FROM design_access_code_batch b WHERE b.deleted = FALSE ORDER BY b.id DESC LIMIT ? OFFSET ?",
                size, (long) Math.max(pageNo - 1, 0) * size);
        var formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        var list = rows.stream().map(r -> {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<String, Object>();
            item.put("id", String.valueOf(((Number) r.get("id")).longValue()));
            item.put("quantity", ((Number) r.get("quantity")).intValue());
            item.put("redeemedCount", ((Number) r.get("redeemed_count")).intValue());
            item.put("deliveryMode", r.get("delivery_mode"));
            item.put("exposedCount", ((Number) r.get("exposed_count")).intValue());
            item.put("issuedBy", r.get("issued_by"));
            item.put("purposeNote", r.get("purpose_note"));
            item.put("expiresAt", r.get("expires_at") == null ? null
                    : formatter.format(((java.sql.Timestamp) r.get("expires_at")).toInstant()));
            item.put("createdAt", formatter.format(((java.sql.Timestamp) r.get("create_time")).toInstant()));
            return item;
        }).toList();
        return success(new cn.iocoder.yudao.framework.common.pojo.PageResult<>(list, total == null ? 0 : total));
    }

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
