package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetEstimateCreateReqVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetEstimateRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 预算测算（页面 13/14）")
@RestController
@RequestMapping("/design/v1")
@PermitAll
public class AppBudgetController {

    @Resource
    private BudgetService budgetService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @PostMapping("/design-projects/{projectId}/budget-estimates")
    @Operation(summary = "创建预算测算：冻结规则版本与输入快照，结果可复算")
    public CommonResult<AppBudgetEstimateRespVO> createEstimate(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppBudgetEstimateCreateReqVO reqVO,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var estimate = budgetService.createEstimate(userId, Long.parseLong(projectId),
                reqVO.getRegionCode(), reqVO.getStructureType(), reqVO.getMaterialGrade(),
                reqVO.getBuildingArea(), reqVO.getResultVersionId());
        return success(toVo(estimate));
    }

    @GetMapping("/design-projects/{projectId}/budget-estimates")
    @Operation(summary = "项目的预算测算列表（历史预算按保存的输入与规则版本展示）")
    public CommonResult<List<AppBudgetEstimateRespVO>> getEstimates(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(budgetService.listByProject(userId, Long.parseLong(projectId)).stream()
                .map(this::toVo).toList());
    }

    @GetMapping("/budget-estimates/{estimateId}")
    @Operation(summary = "预算测算详情（P0 仅为参考区间，不进入正式报价）")
    public CommonResult<AppBudgetEstimateRespVO> getEstimate(
            @PathVariable("estimateId") String estimateId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var estimate = budgetService.getEstimate(userId, Long.parseLong(estimateId))
                .orElseThrow(() -> new AccessDeniedException("测算不存在或无权访问"));
        return success(toVo(estimate));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private AppBudgetEstimateRespVO toVo(BudgetService.Estimate estimate) {
        AppBudgetEstimateRespVO vo = new AppBudgetEstimateRespVO();
        vo.setEstimateId(String.valueOf(estimate.estimateId()));
        vo.setProjectId(String.valueOf(estimate.projectId()));
        vo.setRuleVersion(estimate.ruleVersion());
        vo.setInputSnapshot(estimate.inputSnapshot());
        vo.setTotalMinCents(estimate.totalLowCents());
        vo.setTotalMaxCents(estimate.totalHighCents());
        vo.setDisclaimer(estimate.disclaimer());
        vo.setCreatedAt(estimate.createdAt() == null ? null
                : LocalDateTime.ofInstant(estimate.createdAt(), ZoneId.systemDefault()));
        return vo;
    }

}
