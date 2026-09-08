package cn.iocoder.yudao.module.aiorchestration.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.aiorchestration.controller.app.AppAiJobController;
import cn.iocoder.yudao.module.aiorchestration.controller.app.vo.AppAiJobRespVO;
import cn.iocoder.yudao.module.aiorchestration.enums.PermissionConstants;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - AI 任务运维")
@RestController
@RequestMapping("/design/v1/ai-jobs")
public class AiJobAdminController {

    private final cn.iocoder.yudao.module.aiorchestration.job.AiJobQueryService queryService;

    public AiJobAdminController(cn.iocoder.yudao.module.aiorchestration.job.AiJobQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @Operation(summary = "任务分页查询：状态、阶段；AI 运营不可扣点/审批/公开案例")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AI_JOB_QUERY + "')")
    public CommonResult<PageResult<AppAiJobRespVO>> getJobPage(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "phase", required = false) String phase,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        PageResult<AppAiJobRespVO> result = new PageResult<>();
        result.setTotal(queryService.countJobs(status, phase));
        List<AppAiJobRespVO> list = queryService.pageJobs(status, phase, pageNo, pageSize)
                .stream().map(AppAiJobController::toVo).toList();
        result.setList(list);
        return success(result);
    }

    @GetMapping("/{jobId}")
    @Operation(summary = "任务详情：attempt、租约、结果校验与结算信息")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AI_JOB_QUERY + "')")
    public CommonResult<AppAiJobRespVO> getJob(@PathVariable("jobId") String jobId) {
        var job = queryService.getJob(Long.parseLong(jobId))
                .orElse(null);
        return success(job == null ? null : AppAiJobController.toVo(job));
    }

}
