package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppCaseSummaryRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppHomeRespVO;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import jakarta.annotation.security.PermitAll;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;

import jakarta.annotation.Resource;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 首页（页面 01）")
@RestController
@RequestMapping("/design/v1/home")
@PermitAll // 首页聚合匿名可读（accessGrantStatus 由调用方会话决定）
public class AppHomeController {

    @Resource
    private CaseCatalogService caseCatalogService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping
    @Operation(summary = "首页聚合：精选案例（最新发布）、未读数、使用权状态")
    public CommonResult<AppHomeRespVO> getHome(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        AppHomeRespVO vo = new AppHomeRespVO();
        var featured = caseCatalogService.listPublishedCases(null, null, null, null, null, null, 6);
        vo.setFeaturedCases(featured.list().stream().map(summary -> {
            AppCaseSummaryRespVO s = new AppCaseSummaryRespVO();
            s.setCaseId(String.valueOf(summary.caseId()));
            s.setTitle(summary.title());
            s.setSourceType(summary.sourceType());
            s.setStyleCode(summary.styleCode());
            s.setFloorCount(summary.floorCount());
            s.setBuildingArea(summary.buildingArea());
            return s;
        }).toList());
        vo.setUnreadCount(0L); // P7B 消息中心交付后接入
        // P2-4（报告 15）：按调用方会话返回真实授权态——ANONYMOUS（无/无效会话）、
        // NONE（受限会话）、ACTIVE（已激活），与 /access-grant 语义一致，不再硬编码。
        vo.setAccessGrantStatus(resolveGrantStatus(authorization));
        return success(vo);
    }

    private String resolveGrantStatus(String authorization) {
        if (authorization == null || authorization.isBlank()) {
            return "ANONYMOUS";
        }
        String token = authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
        return identitySessionPort.resolveByBearerToken(token)
                .map(ctx -> ctx.restricted() ? "NONE" : "ACTIVE")
                .orElse("ANONYMOUS");
    }

}
