package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBulkActionResultRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppSubmissionRespVO;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import cn.iocoder.yudao.module.design.submission.SubmissionReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * AI 案例审核（管理端页面 05/06）。
 * 通过（APPROVE）只是状态推进，上架 AI 案例库必须另行调用发布命令（publishApprovedAsCase），
 * 端点见下方 publish 端点——三套状态分离的合同不因管理端便利而合并。
 */
@Tag(name = "管理后台 - AI 案例审核（页面 05/06）")
@RestController
@RequestMapping("/design/v1/submissions")
public class SubmissionAdminController {

    @Resource
    private SubmissionReviewService submissionReviewService;

    @Resource
    private cn.iocoder.yudao.module.design.asset.AssetService assetService;

    @PostMapping("/{submissionId}/assets/{assetId}/preview-tickets")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<Map<String, String>> previewTicket(@PathVariable("submissionId") long submissionId,
                                                           @PathVariable("assetId") long assetId) {
        var ticket = assetService.requestReviewTicket(SecurityFrameworkUtils.getLoginUserId(), submissionId, assetId);
        return success(Map.of("ticket", ticket.getToken()));
    }

    @GetMapping("/{submissionId}/assets/{assetId}/content")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public org.springframework.http.ResponseEntity<byte[]> previewContent(@PathVariable("submissionId") long submissionId,
            @PathVariable("assetId") long assetId, @RequestParam("ticket") String ticket) {
        var content = assetService.readReviewTicket(SecurityFrameworkUtils.getLoginUserId(), submissionId, assetId, ticket);
        return org.springframework.http.ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .contentType(org.springframework.http.MediaType.parseMediaType(content.mimeType()))
                .header("X-Content-Type-Options", "nosniff").body(content.content());
    }

    @GetMapping
    @Operation(summary = "投稿队列分页查询")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<PageResult<AppSubmissionRespVO>> getSubmissionPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AppSubmissionRespVO> result = new PageResult<>();
        result.setTotal(submissionReviewService.countAdminSubmissions(status));
        result.setList(submissionReviewService.pageAdminSubmissions(status, pageNo, pageSize)
                .stream().map(this::toVo).toList());
        return success(result);
    }

    @GetMapping("/{submissionId}")
    @Operation(summary = "投稿审核详情（含最新一轮审核意见、轮次与历轮审核记录）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<AppSubmissionRespVO> getSubmission(@PathVariable("submissionId") String submissionId) {
        var row = submissionReviewService.getAdminSubmission(Long.parseLong(submissionId))
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("投稿不存在"));
        var vo = toVo(row);
        vo.setPreviewAssets(submissionReviewService.previewAssets(row.resultVersionId()));
        vo.setReviewHistory(submissionReviewService.reviewHistory(row.submissionId()));
        return success(vo);
    }

    @PostMapping("/{submissionId}/review-decisions")
    @Operation(summary = "审核决定（APPROVE/CHANGES_REQUESTED/REJECT）；审核人与投稿人分离，审核自己作品拒绝")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<AppSubmissionRespVO> reviewDecision(@PathVariable("submissionId") String submissionId,
                                                            @RequestBody Map<String, Object> decision) {
        long reviewerUserId = SecurityFrameworkUtils.getLoginUserId();
        submissionReviewService.decide(Long.parseLong(submissionId), reviewerUserId,
                (String) decision.get("decision"), (String) decision.get("comment"));
        return success(toVo(submissionReviewService.getAdminSubmission(Long.parseLong(submissionId))
                .orElseThrow()));
    }

    @PostMapping("/bulk-review-commands")
    @Operation(summary = "批量审核命令：逐项幂等，返回逐项结果；单项失败不中断其余项")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<AdminBulkActionResultRespVO> bulkReview(@RequestBody Map<String, Object> command) {
        long reviewerUserId = SecurityFrameworkUtils.getLoginUserId();
        String decision = (String) command.get("decision");
        String comment = (String) command.get("comment");
        @SuppressWarnings("unchecked")
        List<Object> ids = (List<Object>) command.get("submissionIds");
        AdminBulkActionResultRespVO result = new AdminBulkActionResultRespVO();
        List<AdminBulkActionResultRespVO.Item> items = new ArrayList<>();
        if (ids != null) {
            for (Object idObj : ids) {
                long submissionId = Long.parseLong(String.valueOf(idObj));
                AdminBulkActionResultRespVO.Item item = new AdminBulkActionResultRespVO.Item();
                item.setTargetId(String.valueOf(submissionId));
                try {
                    submissionReviewService.decide(submissionId, reviewerUserId, decision, comment);
                    item.setSuccess(true);
                } catch (Exception e) {
                    // 逐项隔离：单条失败（已决定/自审/状态不符）记录原因，其余项继续
                    item.setSuccess(false);
                    item.setErrorCode(e.getMessage());
                }
                items.add(item);
            }
        }
        result.setItems(items);
        return success(result);
    }

    @PostMapping("/{submissionId}/publication-commands")
    @Operation(summary = "发布上架：仅 APPROVED 可发布；创建 AI 案例 + 公开发布事实，重复发布拒绝。"
            + "投稿版本面积为小数时必须携带 confirmedBuildingArea（管理员核定的整数平方米，P1-A）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.SUBMISSION_REVIEW + "')")
    public CommonResult<Map<String, Object>> publish(@PathVariable("submissionId") String submissionId,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        String operator = String.valueOf(SecurityFrameworkUtils.getLoginUserId());
        Integer confirmedArea = null;
        if (body != null && body.get("confirmedBuildingArea") instanceof Number n && n.intValue() > 0) {
            confirmedArea = n.intValue();
        }
        long caseId = submissionReviewService.publishApprovedAsCase(Long.parseLong(submissionId), operator, confirmedArea);
        return success(Map.of("submissionId", submissionId, "publishedCaseId", String.valueOf(caseId)));
    }

    private AppSubmissionRespVO toVo(SubmissionReviewService.SubmissionRow row) {
        AppSubmissionRespVO vo = new AppSubmissionRespVO();
        vo.setSubmissionId(String.valueOf(row.submissionId()));
        vo.setProjectId(String.valueOf(row.projectId()));
        vo.setResultVersionId(String.valueOf(row.resultVersionId()));
        // 管理端必须可见投稿人：审核、回访与审计都以用户维度展开
        vo.setUserId(String.valueOf(row.userId()));
        vo.setStatus(row.status());
        vo.setCurrentRound(row.currentRound());
        vo.setReviewComment(row.reviewComment());
        vo.setNote(row.note());
        vo.setPublicDisplayGranted(row.publicDisplayGranted());
        vo.setGenerationReferenceGranted(row.generationReferenceGranted());
        vo.setSubmittedAt(row.createTime() == null ? null
                : LocalDateTime.ofInstant(row.createTime(), ZoneId.systemDefault()));
        vo.setPublicationStatus(submissionReviewService.publicationStatus(row.publishedCaseId()));
        vo.setAllowedActions(switch (row.status()) {
            case "SUBMITTED", "RESUBMITTED", "IN_REVIEW" -> List.of("APPROVE", "CHANGES_REQUESTED", "REJECT");
            case "APPROVED" -> row.publishedCaseId() == null
                    ? List.of("PUBLISH") : List.of("VIEW");
            case "CHANGES_REQUESTED" -> List.of("VIEW"); // 等待投稿人重提
            default -> List.of("VIEW");
        });
        if (java.util.Objects.equals(SecurityFrameworkUtils.getLoginUserId(), row.userId())) vo.setAllowedActions(List.of("VIEW"));
        return vo;
    }

}
