package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.budget.BudgetInputs;
import cn.iocoder.yudao.module.design.budget.ItemizedBudgetService;
import cn.iocoder.yudao.module.design.budget.BudgetQuoteService;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppItemizedBudgetRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetQuoteRespVO;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetInputsRespVO;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;

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

    @Resource
    private DesignProjectService designProjectService;

    @Resource
    private ItemizedBudgetService itemizedBudgetService;

    @Resource
    private BudgetQuoteService budgetQuoteService;

    @Resource
    private cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricingPort;

    public record BudgetPointQuote(String product, String ruleId, long ruleVersion, long pointCost) { }

    @GetMapping("/budget-point-quotes")
    @Operation(summary = "获取预算测算设计点报价")
    public CommonResult<BudgetPointQuote> getBudgetPointQuote(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        requireAccountId(authorization);
        var quote = usagePricingPort.quote("BUDGET_ESTIMATE");
        return success(new BudgetPointQuote(quote.product(), String.valueOf(quote.ruleId()), quote.ruleVersion(), quote.pointCost()));
    }

    @GetMapping("/design-projects/{projectId}/budget-inputs")
    @Operation(summary = "导入本人项目/方案版本的预算参数与来源，不返回内部工程量或假默认值")
    public CommonResult<AppBudgetInputsRespVO> getBudgetInputs(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "resultVersionId", required = false) String resultVersionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var snapshot = designProjectService.getBudgetInputSnapshot(userId, BudgetInputs.positiveId(projectId),
                resultVersionId == null ? null : BudgetInputs.positiveId(resultVersionId));
        var inputs = BudgetInputs.resolve(snapshot.inputs(), java.util.Map.of());
        return success(new AppBudgetInputsRespVO(projectId, resultVersionId, snapshot.requirementSnapshotIds(),
                inputs.publicValues(), inputs.publicSources(), inputs.missingFields(), inputs.warnings()));
    }

    @PostMapping("/design-projects/{projectId}/budget-estimates")
    @Operation(summary = "创建预算测算：冻结规则版本与输入快照，结果可复算")
    public CommonResult<AppBudgetEstimateRespVO> createEstimate(
            @PathVariable("projectId") String projectId,
            @Valid @RequestBody AppBudgetEstimateCreateReqVO reqVO,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var estimate = budgetService.createEstimate(userId, Long.parseLong(projectId),
                reqVO.getRegionCode(), reqVO.getStructureType(), reqVO.getMaterialGrade(),
                reqVO.getBuildingArea(), reqVO.getResultVersionId(), reqVO.getUsageConfirmation(), key);
        return success(toVo(estimate));
    }

    @PostMapping("/design-projects/{projectId}/budget-estimates/itemized")
    @Operation(summary = "创建并持久化分项预算；服务端查价，缺项结果保留待补状态")
    public CommonResult<AppItemizedBudgetRespVO> createItemizedEstimate(
            @PathVariable("projectId") String projectId,
            @RequestBody Map<String, Object> body,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(itemizedBudgetService.create(userId, BudgetInputs.positiveId(projectId), body, key));
    }

    @GetMapping("/budget-estimates/{estimateId}")
    @Operation(summary = "分模型读取所属预算公开快照；不公开后台未发布修订")
    public CommonResult<Object> getBudget(
            @PathVariable("estimateId") String estimateId,
            @RequestParam(value = "revisionId", required = false) String revisionId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(publicBudget(userId, BudgetInputs.positiveId(estimateId), revisionId == null ? null : BudgetInputs.positiveId(revisionId)));
    }

    @PostMapping("/budget-estimates/{budgetId}/save")
    @Operation(summary = "幂等保存已有分项预算，不重新测算或生成新预算")
    public CommonResult<ItemizedBudgetService.SaveResult> saveBudget(
            @PathVariable("budgetId") String budgetId,
            @RequestHeader("Idempotency-Key") String key,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        return success(itemizedBudgetService.save(userId, BudgetInputs.positiveId(budgetId), key));
    }

    @GetMapping("/design-projects/{projectId}/budget-estimates")
    @Operation(summary = "预算历史；无分页参数保留List，传savedOnly/cursor/limit返回游标分页")
    public CommonResult<Object> getBudgetHistory(
            @PathVariable("projectId") String projectId,
            @RequestParam(value = "savedOnly", required = false) Boolean savedOnly,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", required = false) Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization), project = BudgetInputs.positiveId(projectId);
        if (savedOnly == null && cursor == null && limit == null) {
            var combined = new ArrayList<Object>();
            combined.addAll(budgetService.listByProject(userId, project).stream().map(this::toVo).toList());
            combined.addAll(itemizedBudgetService.listByProject(userId, project));
            combined.sort(Comparator.comparingLong(this::budgetId).reversed());
            return success(combined);
        }
        var refs = itemizedBudgetService.pageByProject(userId, project, Boolean.TRUE.equals(savedOnly),
                cursor == null ? null : BudgetInputs.positiveId(cursor), limit == null ? 20 : limit);
        return success(new CursorPageResult<>(refs.getList().stream()
                .map(reference -> publicBudget(userId, BudgetInputs.positiveId(reference.budgetId()), null)).toList(), refs.getNextCursor()));
    }

    @GetMapping("/design-projects/{projectId}/budget-quotes")
    @Operation(summary = "读取本人项目已发布/已撤回报价历史；草稿和内部调整原因永不公开")
    public CommonResult<List<AppBudgetQuoteRespVO>> getBudgetQuotes(
            @PathVariable("projectId") String projectId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(budgetQuoteService.appList(requireAccountId(authorization), projectId));
    }

    @GetMapping("/design-projects/{projectId}/budget-quotes/{quoteId}")
    @Operation(summary = "读取本人项目指定公开报价快照")
    public CommonResult<AppBudgetQuoteRespVO> getBudgetQuote(
            @PathVariable("projectId") String projectId, @PathVariable("quoteId") String quoteId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(budgetQuoteService.appGet(requireAccountId(authorization), projectId, quoteId));
    }

    private Object publicBudget(long userId, long budgetId, Long revisionId) {
        var itemized = itemizedBudgetService.get(userId, budgetId, revisionId);
        if (itemized.isPresent()) return itemized.get();
        return toVo(budgetService.getEstimate(userId, budgetId).orElseThrow(() -> new AccessDeniedException("测算不存在或无权访问")));
    }

    private long budgetId(Object value) {
        return Long.parseLong(value instanceof AppItemizedBudgetRespVO itemized ? itemized.budgetId() : ((AppBudgetEstimateRespVO) value).getEstimateId());
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
