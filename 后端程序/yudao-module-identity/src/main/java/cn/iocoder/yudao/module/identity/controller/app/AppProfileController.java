package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppProfileRespVO;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * "我的"页基础信息（页面 15）。
 *
 * 只下发身份与授权态：设计点余额走 GET /design/v1/point-account，
 * 设计/投稿统计走各自模块的列表端点——本端点不跨模块聚合，保持四模块零依赖。
 */
@Tag(name = "小程序 - 个人信息")
@RestController
@RequestMapping("/design/v1/profile")
@PermitAll
public class AppProfileController {

    @Resource
    private UserSessionService sessionService;

    @Resource
    private AccountLoginService accountLoginService;

    @Resource
    private javax.sql.DataSource dataSource;

    @GetMapping
    @Operation(summary = "获取“我的”页基础信息（身份与授权态 + 设计/发布统计；余额走独立端点）")
    public CommonResult<AppProfileRespVO> getProfile(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        var profile = accountLoginService.findProfile(context.accountId())
                .orElseThrow(() -> new AccessDeniedException("账号不存在"));
        AppProfileRespVO vo = new AppProfileRespVO();
        vo.setId(profile.accountId());
        vo.setNickname(profile.nickname());
        vo.setAvatar(profile.avatar());
        vo.setStatus(profile.status());
        // restricted 由会话校验实时联表判定，撤销授权后下一次请求即降级
        vo.setAccessGrantStatus(context.restricted() ? "NONE" : "ACTIVE");
        vo.setAllowedActions(context.restricted()
                ? List.of("REDEEM_ACCESS_CODE", "VIEW_STATUS", "CONTACT_SUPPORT")
                : List.of("VIEW_POINTS", "VIEW_PROJECTS", "RECHARGE", "CONTACT_SUPPORT"));
        vo.setStats(buildStats(profile.accountId()));
        return success(vo);
    }

    /**
     * 设计/发布统计（审查 C9）：「我的」页是天然的跨域读模型，
     * 沿用工作台先例（DashboardAdminController）——各业务模块不对外暴露计数端点，
     * 为此引入跨模块服务调用反而把展示需求传染进领域层；计数走参数化只读 SQL。
     */
    private Map<String, Integer> buildStats(long accountId) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Map<String, Integer> stats = new java.util.LinkedHashMap<>();
        stats.put("projectCount", (int) count(jdbc,
                "SELECT count(*) FROM design_project WHERE user_id = ? AND deleted = FALSE", accountId));
        stats.put("publishedCount", (int) count(jdbc,
                "SELECT count(*) FROM case_submission WHERE user_id = ? AND status = 'APPROVED' AND deleted = FALSE",
                accountId));
        return stats;
    }

    private long count(org.springframework.jdbc.core.JdbcTemplate jdbc, String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    @PatchMapping("/preferences")
    @Operation(summary = "更新用户偏好设置（字段白名单由服务端决定，白名单外的键丢弃）")
    public CommonResult<Boolean> updatePreferences(
            @RequestBody Map<String, Object> preferences,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        UserSessionService.AccessContext context = requireContext(authorization);
        return success(accountLoginService.updatePreferences(context.accountId(), preferences));
    }

    private UserSessionService.AccessContext requireContext(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new AccessDeniedException("会话无效或已过期"));
    }

}
