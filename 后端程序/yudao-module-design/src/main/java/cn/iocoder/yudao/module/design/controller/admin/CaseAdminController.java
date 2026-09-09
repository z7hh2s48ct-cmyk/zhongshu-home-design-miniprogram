package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBulkActionResultRespVO;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminCaseRespVO;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 公司案例与发布（页面 03/04）")
@RestController
@RequestMapping("/design/v1/cases")
public class CaseAdminController {

    @Resource
    private CaseCatalogService caseCatalogService;

    @Resource
    private cn.iocoder.yudao.module.design.asset.AssetService assetService;

    @Resource
    private cn.iocoder.yudao.module.design.catalog.CompanyCaseImageService companyCaseImageService;

    @PostMapping(value = "/{caseId}/images", consumes = "multipart/form-data")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_WRITE + "')")
    public CommonResult<cn.iocoder.yudao.module.design.catalog.CompanyCaseImageService.Uploaded> uploadImage(
            @PathVariable("caseId") long caseId, @RequestParam("version") long version,
            @RequestParam("role") String role, @RequestParam(value="floorNo", required=false) Integer floorNo,
            @RequestParam("publicDisplay") boolean publicDisplay,
            @RequestParam(value="generationReference", defaultValue="false") boolean generationReference,
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) throws java.io.IOException {
        if (file.getSize() > cn.iocoder.yudao.module.design.asset.AssetTypePolicy.CASE_IMAGE.maxBytes())
            throw new IllegalArgumentException("图片不能超过20MB");
        return success(companyCaseImageService.upload(caseId, SecurityFrameworkUtils.getLoginUserId(), version,
                role, floorNo, file.getContentType(), file.getBytes(), publicDisplay, generationReference));
    }

    @GetMapping("/{caseId}")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_READ + "')")
    public CommonResult<Map<String, Object>> getCase(@PathVariable("caseId") long caseId) {
        var detail = caseCatalogService.getAdminCase(caseId).orElseThrow(() -> exception(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN));
        var view = new java.util.LinkedHashMap<String, Object>();
        view.put("caseId", String.valueOf(detail.caseId()));
        view.put("title", detail.title()); view.put("description", detail.description());
        view.put("sourceType", detail.sourceType()); view.put("publicationStatus", detail.publicationStatus());
        view.put("version", detail.version()); view.put("styleCode", detail.styleCode());
        view.put("floorCount", detail.floorCount()); view.put("buildingArea", detail.buildingArea());
        view.put("faceWidth", detail.faceWidth()); view.put("depth", detail.depth());
        view.put("coverAssetId", detail.coverAssetId()); view.put("elevationAssetId", detail.elevationAssetId());
        view.put("floorPlans", caseCatalogService.getFloorPlans(caseId));
        return success(view);
    }

    @PostMapping("/{caseId}/assets/{assetId}/preview-tickets")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_READ + "')")
    public CommonResult<Map<String, Object>> previewTicket(@PathVariable("caseId") long caseId, @PathVariable("assetId") long assetId) {
        return success(Map.of("ticket", assetService.requestCasePreviewTicket(SecurityFrameworkUtils.getLoginUserId(), caseId, assetId).getToken()));
    }

    @GetMapping("/{caseId}/assets/{assetId}/content")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_READ + "')")
    public org.springframework.http.ResponseEntity<byte[]> previewContent(@PathVariable("caseId") long caseId,
            @PathVariable("assetId") long assetId, @RequestParam("ticket") String ticket) {
        var content = assetService.readCasePreviewTicket(SecurityFrameworkUtils.getLoginUserId(), caseId, assetId, ticket);
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType(content.mimeType()))
                .header("X-Content-Type-Options", "nosniff").body(content.content());
    }

    @GetMapping
    @Operation(summary = "案例分页查询（公司/AI 来源、状态筛选）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_READ + "')")
    public CommonResult<PageResult<AdminCaseRespVO>> getCasePage(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "publicationStatus", required = false) String publicationStatus,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AdminCaseRespVO> result = new PageResult<>();
        result.setTotal(caseCatalogService.countCasesForAdmin(sourceType, publicationStatus));
        result.setList(caseCatalogService.pageCasesForAdmin(sourceType, publicationStatus, pageNo, pageSize)
                .stream().map(this::toVo).toList());
        return success(result);
    }

    @PostMapping
    @Operation(summary = "新增公司案例（草稿）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_CREATE + "')")
    public CommonResult<AdminCaseRespVO> createCase(@RequestBody Map<String, Object> body) {
        long admin = SecurityFrameworkUtils.getLoginUserId();
        long caseId = caseCatalogService.createCompanyCase(admin,
                (String) body.get("title"),
                (String) body.get("description"),
                (String) body.getOrDefault("styleCode", "MODERN"),
                ((Number) body.getOrDefault("floorCount", 1)).intValue(),
                ((Number) body.getOrDefault("buildingArea", 100)).intValue(),
                body.get("faceWidth") == null ? null : ((Number) body.get("faceWidth")).intValue(),
                body.get("depth") == null ? null : ((Number) body.get("depth")).intValue(),
                null, null);
        AdminCaseRespVO vo = new AdminCaseRespVO();
        vo.setCaseId(String.valueOf(caseId));
        vo.setSourceType("COMPANY");
        vo.setPublicationStatus("DRAFT");
        vo.setVersion(1L);
        return success(vo);
    }

    @PatchMapping("/{caseId}")
    @Operation(summary = "编辑案例：生成新版本；body.version 过期返回 STATE_VERSION_CONFLICT")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_WRITE + "')")
    public CommonResult<AdminCaseRespVO> updateCase(@PathVariable("caseId") String caseId,
                                                    @RequestBody Map<String, Object> body) {
        long admin = SecurityFrameworkUtils.getLoginUserId();
        long expectedVersion = ((Number) body.get("version")).longValue();
        var current = caseCatalogService.getAdminCase(Long.parseLong(caseId))
                .orElseThrow(() -> exception(ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN));
        caseCatalogService.updateCase(Long.parseLong(caseId), admin, expectedVersion,
                (String) body.getOrDefault("title", ""),
                (String) body.get("description"),
                (String) body.getOrDefault("styleCode", "MODERN"),
                ((Number) body.getOrDefault("floorCount", 1)).intValue(),
                ((Number) body.getOrDefault("buildingArea", 100)).intValue(),
                body.get("faceWidth") == null ? null : ((Number) body.get("faceWidth")).intValue(),
                body.get("depth") == null ? null : ((Number) body.get("depth")).intValue(),
                current.rooms(), current.tags());
        AdminCaseRespVO vo = new AdminCaseRespVO();
        vo.setCaseId(caseId);
        vo.setVersion(expectedVersion + 1);
        return success(vo);
    }

    @PostMapping("/{caseId}/publications")
    @Operation(summary = "上架发布（独立发布命令；AI 案例必须审核通过且公开展示授权有效）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_PUBLISH + "')")
    public CommonResult<Boolean> publish(@PathVariable("caseId") String caseId) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        return success(caseCatalogService.publish(Long.parseLong(caseId), operator));
    }

    @PostMapping("/{caseId}/withdrawals")
    @Operation(summary = "下架：即时不可见，留下架事实与原因")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_PUBLISH + "')")
    public CommonResult<Boolean> withdraw(@PathVariable("caseId") String caseId,
                                          @RequestBody(required = false) Map<String, Object> reason) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        return success(caseCatalogService.offline(Long.parseLong(caseId),
                reason == null ? null : String.valueOf(reason.get("reason")), operator));
    }

    @PostMapping("/bulk-actions")
    @Operation(summary = "批量上下架：逐项返回成功/失败，不静默半成功")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.CASE_PUBLISH + "')")
    public CommonResult<AdminBulkActionResultRespVO> bulkActions(@RequestBody Map<String, Object> command) {
        @SuppressWarnings("unchecked")
        List<Object> caseIds = (List<Object>) command.get("caseIds");
        boolean publish = Boolean.TRUE.equals(command.get("publish"));
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        var items = caseCatalogService.bulkPublicationAction(
                caseIds.stream().map(id -> Long.parseLong(String.valueOf(id))).toList(), publish,
                (String) command.get("reason"), operator);
        AdminBulkActionResultRespVO vo = new AdminBulkActionResultRespVO();
        vo.setItems(items.stream().map(item -> {
            AdminBulkActionResultRespVO.Item i = new AdminBulkActionResultRespVO.Item();
            i.setTargetId((String) item.get("targetId"));
            i.setSuccess((Boolean) item.get("success"));
            i.setErrorCode((String) item.get("errorCode"));
            return i;
        }).toList());
        return success(vo);
    }

    private AdminCaseRespVO toVo(CaseCatalogService.CaseSummary summary) {
        AdminCaseRespVO vo = new AdminCaseRespVO();
        vo.setCaseId(String.valueOf(summary.caseId()));
        vo.setTitle(summary.title());
        vo.setSourceType(summary.sourceType());
        vo.setStyleCode(summary.styleCode());
        vo.setFloorCount(summary.floorCount());
        vo.setBuildingArea(summary.buildingArea());
        vo.setPublicationStatus(summary.publicationStatus());
        vo.setVersion(summary.version());
        vo.setUpdatedAt(summary.updatedAt() == null ? null
                : java.time.LocalDateTime.ofInstant(summary.updatedAt(),
                        java.time.ZoneId.systemDefault()));
        return vo;
    }

}
