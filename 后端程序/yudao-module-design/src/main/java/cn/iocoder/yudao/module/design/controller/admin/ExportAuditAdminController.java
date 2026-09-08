package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.design.enums.PermissionConstants;
import jakarta.annotation.Resource;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "管理后台 - 异步导出与审计")
@RestController
@RequestMapping("/design/v1")
public class ExportAuditAdminController {

    @Resource
    private cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort deliveryPort;

    @Resource
    private javax.sql.DataSource dataSource;

    @Resource
    private cn.iocoder.yudao.module.design.asset.ObjectStoragePort storage;

    @PostMapping("/export-jobs")
    @Operation(summary = "创建异步导出任务（大批量不占请求线程；记录申请人、过滤条件、字段范围）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Map<String, Object>> createExportJob(@RequestBody Map<String, Object> command) {
        long jobId = deliveryPort.createExportJob(cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobRequest.builder()
                .jobType(String.valueOf(command.getOrDefault("jobType", "GENERIC")))
                .requesterType("ADMIN")
                .requesterUserId(SecurityFrameworkUtils.getLoginUserId())
                .filterSnapshot(command)
                .build());
        return success(Map.of("exportJobId", String.valueOf(jobId), "status", "PENDING"));
    }

    @GetMapping("/export-jobs/{exportJobId}")
    @Operation(summary = "查询导出任务状态")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Map<String, Object>> getExportJob(@PathVariable("exportJobId") String exportJobId) {
        var job = deliveryPort.getExportJob(Long.parseLong(exportJobId));
        if (job == null) {
            return success(null);
        }
        return success(Map.of("exportJobId", String.valueOf(job.getJobId()),
                "jobType", job.getJobType(), "status", job.getStatus().name(),
                "fileAssetId", job.getFileAssetId() == null ? "" : job.getFileAssetId(),
                "expiresAt", job.getExpiresAt() == null ? "" : job.getExpiresAt().toString()));
    }

    @PostMapping("/export-jobs/{exportJobId}/download-tickets")
    @Operation(summary = "生成导出文件的一次性下载票据（短时、单次、留审计）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Map<String, Object>> createDownloadTicket(@PathVariable("exportJobId") String exportJobId) {
        var job = deliveryPort.getExportJob(Long.parseLong(exportJobId));
        if (job == null || !"COMPLETED".equals(job.getStatus().name())) {
            return success(Map.of("ticket", "", "error", "EXPORT_NOT_COMPLETED"));
        }
        var ticket = deliveryPort.issueDownloadTicket("EXPORT_FILE",
                String.valueOf(job.getJobId()), SecurityFrameworkUtils.getLoginUserId(), 600);
        return success(Map.of("ticket", ticket.getToken(),
                "expiresAt", ticket.getExpiresAt() == null ? "" : ticket.getExpiresAt().toString()));
    }

    @GetMapping("/export-jobs/{exportJobId}/content")
    @Operation(summary = "凭一次性票据流式输出导出文件（C10：票据→字节闭环；管理端 blob 下载）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public org.springframework.http.ResponseEntity<byte[]> downloadExportFile(
            @PathVariable("exportJobId") String exportJobId,
            @RequestParam("ticket") String ticket) {
        long jobId = Long.parseLong(exportJobId);
        var consumption = deliveryPort.consumeDownloadTicket(ticket,
                String.valueOf(SecurityFrameworkUtils.getLoginUserId()));
        if (consumption.getOutcome() != cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption.Outcome.CONSUMED_NOW
                || !"EXPORT_FILE".equals(consumption.getPurpose())
                || !String.valueOf(jobId).equals(consumption.getBizRef())) {
            throw new org.springframework.security.access.AccessDeniedException("下载票据无效");
        }
        var job = deliveryPort.getExportJob(jobId);
        if (job == null || job.getFileAssetId() == null || job.getFileAssetId().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("导出文件不存在");
        }
        byte[] content;
        try (java.io.InputStream in = storage.getObject(job.getFileAssetId())) {
            content = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new org.springframework.security.access.AccessDeniedException("导出文件读取失败");
        }
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.set("Content-Disposition", "attachment; filename=export-" + jobId + ".csv");
        return org.springframework.http.ResponseEntity.ok().headers(headers).body(content);
    }

    @GetMapping("/audit-events")
    @Operation(summary = "审计事件分页查询（P1C audit_event）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AUDIT_READ + "')")
    public CommonResult<PageResult<Map<String, Object>>> getAuditEvents(
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        long total = jdbc.queryForObject("SELECT count(*) FROM audit_event", Long.class);
        var list = jdbc.queryForList(
                "SELECT id, event_type, actor_type, actor_id, action, biz_type, biz_id, result, "
                        + "detail::text, create_time FROM audit_event ORDER BY id DESC LIMIT ? OFFSET ?",
                Math.min(pageSize, 100), (long) Math.max(pageNo - 1, 0) * pageSize);
        return success(new PageResult<>(list, total));
    }

}
