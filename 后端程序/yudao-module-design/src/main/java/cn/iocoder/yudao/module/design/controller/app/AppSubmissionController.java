package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppPublicationValidationRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppSubmissionCreateReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppSubmissionRespVO;
import cn.iocoder.yudao.module.design.submission.SubmissionReviewService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * 投稿与发布（页面 12）。
 * 发布是独立命令：审核通过（APPROVED）只是必要条件，上架由后台运营执行
 * publishApprovedAsCase；小程序端永远看不到"通过即自动上架"。
 */
@Tag(name = "小程序 - 投稿与发布（页面 12）")
@RestController
@RequestMapping("/design/v1")
@PermitAll
public class AppSubmissionController {

    @Resource
    private SubmissionReviewService submissionReviewService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping("/design-projects/{projectId}/publication-validations")
    @Operation(summary = "服务端发布校核：返回缺失字段与授权状态，校核失败给出字段明细")
    public CommonResult<AppPublicationValidationRespVO> validatePublication(
            @PathVariable("projectId") String projectId,
            @RequestBody(required = false) AppSubmissionCreateReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        // 校核是提交的前置只读步骤：缺 resultVersionId 时给出明确缺口而不是 500
        String versionId = reqVO == null ? null : reqVO.getResultVersionId();
        AppPublicationValidationRespVO vo = new AppPublicationValidationRespVO();
        if (versionId == null || versionId.isBlank()) {
            vo.setValid(false);
            vo.setMissingFields(List.of("resultVersionId: 必须指定要发布的最终结果版本"));
            vo.setMessage("请先生成并选定立面方案");
            return success(vo);
        }
        List<String> missing = submissionReviewService.validateForPublication(
                userId, Long.parseLong(projectId), Long.parseLong(versionId));
        vo.setValid(missing.isEmpty());
        vo.setMissingFields(missing);
        vo.setMessage(missing.isEmpty() ? "校核通过，可提交投稿" : "存在缺失项，补齐后才能提交");
        return success(vo);
    }

    @PostMapping("/design-projects/{projectId}/submissions")
    @Operation(summary = "提交投稿（幂等键 = project_id + result_version_id + Idempotency-Key）")
    public CommonResult<AppSubmissionRespVO> createSubmission(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppSubmissionCreateReqVO reqVO,
            @Parameter(description = "幂等键")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        long submissionId = submissionReviewService.submit(userId, Long.parseLong(projectId),
                Long.parseLong(reqVO.getResultVersionId()),
                Boolean.TRUE.equals(reqVO.getPublicDisplayGranted()),
                Boolean.TRUE.equals(reqVO.getGenerationReferenceGranted()),
                reqVO.getNote(), idempotencyKey);
        return success(toVo(submissionReviewService.getMySubmission(userId, submissionId)
                .orElseThrow(() -> new IllegalStateException("投稿创建后回读失败: " + submissionId))));
    }

    @GetMapping("/submissions")
    @Operation(summary = "我的投稿列表（游标分页，独立空态）")
    public CommonResult<CursorPageResult<AppSubmissionRespVO>> getSubmissionPage(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        Long beforeId = cursor == null || cursor.isBlank() ? null : Long.parseLong(cursor);
        // 上限 49 而非 50：服务层在内部多取一行判断截断，控制器需要至少 1 行余量判 hasMore
        int size = Math.min(Math.max(limit, 1), 49);
        var rows = submissionReviewService.listMySubmissions(userId, beforeId, size + 1);
        boolean hasMore = rows.size() > size;
        List<SubmissionReviewService.SubmissionRow> page = hasMore ? rows.subList(0, size) : rows;
        String nextCursor = hasMore && !page.isEmpty()
                ? String.valueOf(page.get(page.size() - 1).submissionId()) : null;
        return success(new CursorPageResult<>(
                page.stream().map(this::toVo).toList(), nextCursor));
    }

    @GetMapping("/submissions/{submissionId}")
    @Operation(summary = "投稿详情（含最新一轮审核意见与轮次）")
    public CommonResult<AppSubmissionRespVO> getSubmission(
            @PathVariable("submissionId") String submissionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var row = submissionReviewService.getMySubmission(userId, Long.parseLong(submissionId))
                .orElseThrow(() -> new AccessDeniedException("投稿不存在或无权访问"));
        return success(toVo(row));
    }

    @PostMapping("/submissions/{submissionId}/resubmissions")
    @Operation(summary = "修改后重提：仅 CHANGES_REQUESTED 可用；新建 revision，不覆盖旧提交")
    public CommonResult<AppSubmissionRespVO> resubmit(
            @PathVariable("submissionId") String submissionId,
            @RequestBody(required = false) java.util.Map<String, String> body,
            @Parameter(description = "幂等键")
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(toVo(submissionReviewService.resubmit(userId, Long.parseLong(submissionId),
                body == null ? null : body.get("note"), idempotencyKey)));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private AppSubmissionRespVO toVo(SubmissionReviewService.SubmissionRow row) {
        AppSubmissionRespVO vo = new AppSubmissionRespVO();
        vo.setSubmissionId(String.valueOf(row.submissionId()));
        vo.setProjectId(String.valueOf(row.projectId()));
        vo.setResultVersionId(String.valueOf(row.resultVersionId()));
        vo.setStatus(row.status());
        vo.setCurrentRound(row.currentRound());
        vo.setReviewComment(row.reviewComment());
        vo.setNote(row.note());
        vo.setPublicDisplayGranted(row.publicDisplayGranted());
        vo.setGenerationReferenceGranted(row.generationReferenceGranted());
        vo.setSubmittedAt(row.createTime() == null ? null
                : LocalDateTime.ofInstant(row.createTime(), ZoneId.systemDefault()));
        vo.setPublicationStatus(submissionReviewService.publicationStatus(row.publishedCaseId()));
        vo.setAllowedActions("CHANGES_REQUESTED".equals(row.status())
                ? List.of("RESUBMIT")
                : ("APPROVED".equals(row.status()) ? List.of("VIEW") : List.of("VIEW")));
        return vo;
    }

}
