package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseDetailRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseSummaryRespVO;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 户型库与收藏（页面 02/03）")
@RestController
@RequestMapping("/design/v1")
@PermitAll // 列表/详情匿名可读；favorite 系列方法内强制 Bearer 校验
public class AppCaseController {

    @Resource
    private CaseCatalogService caseCatalogService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping("/cases")
    @Operation(summary = "户型库列表：来源/风格/层数/面积筛选，稳定游标分页")
    public CommonResult<CursorPageResult<AppCaseSummaryRespVO>> getCasePage(
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "styleCode", required = false) String styleCode,
            @RequestParam(value = "floorCount", required = false) Integer floorCount,
            @RequestParam(value = "minArea", required = false) Integer minArea,
            @RequestParam(value = "maxArea", required = false) Integer maxArea,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit) {
        var page = caseCatalogService.listPublishedCases(sourceType, styleCode, floorCount,
                minArea, maxArea, cursor, limit);
        return success(new CursorPageResult<>(page.list().stream().map(this::toSummaryVo).toList(),
                page.nextCursor()));
    }

    @GetMapping("/cases/{caseId}")
    @Operation(summary = "户型详情：结构化参数、封面、各层平面、立面、来源")
    public CommonResult<AppCaseDetailRespVO> getCase(@PathVariable("caseId") String caseId) {
        var detail = caseCatalogService.getPublishedCase(Long.parseLong(caseId))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("案例不存在或未发布"));
        AppCaseDetailRespVO vo = new AppCaseDetailRespVO();
        vo.setCaseId(String.valueOf(detail.caseId()));
        vo.setTitle(detail.title());
        vo.setDescription(detail.description());
        vo.setSourceType(detail.sourceType());
        vo.setCoverAssetId(detail.coverAssetId());
        vo.setFloorPlanAssetIds(detail.floorPlanAssetIds());
        vo.setFloorPlans(caseCatalogService.getFloorPlans(detail.caseId()));
        vo.setElevationAssetId(detail.elevationAssetId());
        vo.setPdfAssetId(detail.pdfAssetId());
        vo.setVersion(detail.version());
        vo.setParameters(java.util.Map.of(
                "styleCode", detail.styleCode(),
                "floorCount", detail.floorCount(),
                "buildingArea", detail.buildingArea(),
                "faceWidth", detail.faceWidth() == null ? 0 : detail.faceWidth(),
                "depth", detail.depth() == null ? 0 : detail.depth()));
        boolean canDesign = caseCatalogService.canUseForGeneration(detail.caseId());
        vo.setAllowedActions(canDesign ? List.of("FAVORITE", "DESIGN_WITH") : List.of("FAVORITE"));
        vo.setDesignUnavailableReason(canDesign ? null : "该案例暂无有效的设计参考授权，可浏览和收藏");
        return success(vo);
    }

    @PutMapping("/cases/{caseId}/favorite")
    @Operation(summary = "收藏案例（并发幂等）")
    public CommonResult<Boolean> favorite(@PathVariable("caseId") String caseId,
                                          @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(caseCatalogService.favorite(requireAccountId(authorization), Long.parseLong(caseId)));
    }

    @DeleteMapping("/cases/{caseId}/favorite")
    @Operation(summary = "取消收藏")
    public CommonResult<Boolean> unfavorite(@PathVariable("caseId") String caseId,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(caseCatalogService.unfavorite(requireAccountId(authorization), Long.parseLong(caseId)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "收藏列表（游标分页；仅已发布案例）")
    public CommonResult<CursorPageResult<AppCaseSummaryRespVO>> getFavorites(
            @Parameter(description = "游标") @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        var page = caseCatalogService.listFavorites(requireAccountId(authorization), cursor, limit);
        return success(new CursorPageResult<>(page.list().stream().map(this::toSummaryVo).toList(),
                page.nextCursor()));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private AppCaseSummaryRespVO toSummaryVo(CaseCatalogService.CaseSummary summary) {
        AppCaseSummaryRespVO vo = new AppCaseSummaryRespVO();
        vo.setCaseId(String.valueOf(summary.caseId()));
        vo.setTitle(summary.title());
        vo.setSourceType(summary.sourceType());
        vo.setStyleCode(summary.styleCode());
        vo.setFloorCount(summary.floorCount());
        vo.setBuildingArea(summary.buildingArea());
        vo.setCoverAssetId(summary.coverAssetId());
        vo.setAllowedActions(caseCatalogService.canUseForGeneration(summary.caseId())
                ? List.of("FAVORITE", "DESIGN_WITH") : List.of("FAVORITE"));
        return vo;
    }

}
