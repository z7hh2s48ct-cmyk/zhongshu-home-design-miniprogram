package cn.iocoder.yudao.module.identity.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.identity.controller.app.vo.AppSubjectRequestRespVO;
import cn.iocoder.yudao.module.identity.privacy.PrivacyService;
import cn.iocoder.yudao.module.identity.session.UserSessionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 数据主体请求（架构 §6.10）：数据导出与账号关闭。
 * P0 只登记与查询；导出包生成与账号关闭编排由后续包接入，
 * 届时补 download_ticket 与 completed_at，状态机已由表约束固化。
 */
@Tag(name = "小程序 - 数据主体请求（导出/关闭账号）")
@RestController
@RequestMapping("/design/v1")
@PermitAll
public class AppDataSubjectRequestController {

    @Resource
    private PrivacyService privacyService;

    @Resource
    private UserSessionService sessionService;

    @PostMapping("/data-export-requests")
    @Operation(summary = "发起数据导出请求（同类型未完结时幂等返回既有请求）")
    public CommonResult<AppSubjectRequestRespVO> createDataExportRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(toVo(privacyService.createSubjectRequest(
                requireAccountId(authorization), "EXPORT")));
    }

    @GetMapping("/data-export-requests/{requestId}")
    @Operation(summary = "查询导出请求进度（downloadTicket 就绪后凭票据一次性下载）")
    public CommonResult<AppSubjectRequestRespVO> getDataExportRequest(
            @PathVariable("requestId") String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(getRequest(authorization, requestId));
    }

    @PostMapping("/account-closure-requests")
    @Operation(summary = "发起账号关闭请求（P0 只登记；关闭编排与账务留存随后续包接入）")
    public CommonResult<AppSubjectRequestRespVO> createAccountClosureRequest(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(toVo(privacyService.createSubjectRequest(
                requireAccountId(authorization), "CLOSE_ACCOUNT")));
    }

    @GetMapping("/account-closure-requests/{requestId}")
    @Operation(summary = "查询账号关闭请求进度")
    public CommonResult<AppSubjectRequestRespVO> getAccountClosureRequest(
            @PathVariable("requestId") String requestId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(getRequest(authorization, requestId));
    }

    private AppSubjectRequestRespVO getRequest(String authorization, String requestId) {
        long userId = requireAccountId(authorization);
        var request = privacyService.getSubjectRequest(userId, Long.parseLong(requestId))
                // 查询条件含 user_id，他人的请求与不存在的请求返回一致，不给探测面
                .orElseThrow(() -> new AccessDeniedException("请求不存在或无权访问"));
        return toVo(request);
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        return sessionService.validateAccessToken(token)
                .orElseThrow(() -> new cn.iocoder.yudao.framework.common.exception.ServiceException(401, "会话无效或已过期"))
                .accountId();
    }

    private AppSubjectRequestRespVO toVo(PrivacyService.SubjectRequest request) {
        AppSubjectRequestRespVO vo = new AppSubjectRequestRespVO();
        vo.setRequestId(String.valueOf(request.requestId()));
        vo.setStatus(request.status());
        vo.setCreatedAt(request.createdAt() == null ? null
                : LocalDateTime.ofInstant(request.createdAt(), ZoneId.systemDefault()));
        vo.setCompletedAt(request.completedAt() == null ? null
                : LocalDateTime.ofInstant(request.completedAt(), ZoneId.systemDefault()));
        vo.setDownloadTicket(request.downloadTicket());
        return vo;
    }

}
