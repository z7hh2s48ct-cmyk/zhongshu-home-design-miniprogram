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
        var filters = cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportFilters.validate(command);
        long jobId = deliveryPort.createExportJob(cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobRequest.builder()
                .jobType(String.valueOf(filters.get("jobType")))
                .requesterType("ADMIN")
                .requesterUserId(SecurityFrameworkUtils.getLoginUserId())
                .filterSnapshot(filters)
                .build());
        return success(Map.of("exportJobId", String.valueOf(jobId), "status", "PENDING"));
    }

    @GetMapping("/export-jobs")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<PageResult<Map<String, Object>>> getExportJobs(
            @RequestParam(value = "pageNo", defaultValue = "1") int pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Long owner = SecurityFrameworkUtils.getLoginUserId();
        int size = Math.max(1, Math.min(pageSize, 100));
        Long total = jdbc.queryForObject("SELECT count(*) FROM export_job WHERE requester_type = 'ADMIN' "
                + "AND requester_user_id = ?", Long.class, owner);
        var ids = jdbc.queryForList("SELECT id FROM export_job WHERE requester_type = 'ADMIN' "
                + "AND requester_user_id = ? ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                Long.class, owner, size, (long) Math.max(0, pageNo - 1) * size);
        return success(new PageResult<>(ids.stream().map(id -> exportView(requireOwnedJob(id))).toList(), total));
    }

    private cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobSnapshot requireOwnedJob(long id) {
        var job = deliveryPort.getExportJob(id);
        if (job == null || !"ADMIN".equals(job.getRequesterType())
                || !java.util.Objects.equals(job.getRequesterUserId(), SecurityFrameworkUtils.getLoginUserId())) {
            throw new org.springframework.security.access.AccessDeniedException("导出任务不存在或无权访问");
        }
        return job;
    }

    private String exportStatus(cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobSnapshot job) {
        return job.getExpiresAt() != null && !job.getExpiresAt().isAfter(java.time.Instant.now())
                ? "EXPIRED" : job.getStatus().name();
    }

    private Map<String, Object> exportView(cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobSnapshot job) {
        String error = job.getError();
        if (error != null && !java.util.Set.of("EXPORT_ROW_LIMIT", "EXPORT_SIZE_LIMIT", "EXPORT_RETRY_LIMIT",
                "EXPORT_LEASE_LOST", "EXPORT_CANCELLED").contains(error)) error = "EXPORT_FAILED";
        return Map.of("exportJobId", String.valueOf(job.getJobId()), "jobType", job.getJobType(),
                "status", exportStatus(job), "createdAt", job.getCreateTime() == null ? "" : job.getCreateTime().toString(),
                "expiresAt", job.getExpiresAt() == null ? "" : job.getExpiresAt().toString(),
                "error", error == null ? "" : error);
    }

    private cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportJobSnapshot requireDownloadableJob(long id) {
        var job = requireOwnedJob(id);
        if (!"COMPLETED".equals(exportStatus(job)) || job.getExpiresAt() == null
                || job.getFileAssetId() == null || job.getFileAssetId().isBlank()) {
            throw new org.springframework.security.access.AccessDeniedException("导出尚未完成或已过期，请重新创建任务");
        }
        return job;
    }

    @GetMapping("/export-jobs/{exportJobId}")
    @Operation(summary = "查询导出任务状态")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Map<String, Object>> getExportJob(@PathVariable("exportJobId") String exportJobId) {
        return success(exportView(requireOwnedJob(Long.parseLong(exportJobId))));
    }

    @PostMapping("/export-jobs/{exportJobId}/cancellation")
    @Operation(summary = "取消本人排队中/生成中的导出任务；已被 worker 认领到尾段的仍可能完成")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Boolean> cancelExportJob(@PathVariable("exportJobId") String exportJobId) {
        long id = Long.parseLong(exportJobId);
        requireOwnedJob(id);
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        int updated = jdbc.update(
                "UPDATE export_job SET cancel_requested = TRUE, update_time = now() "
                        + "WHERE id = ? AND status IN ('PENDING','RUNNING')", id);
        return success(updated == 1);
    }

    @PostMapping("/export-jobs/{exportJobId}/download-tickets")
    @Operation(summary = "生成导出文件的一次性下载票据（短时、单次、留审计）")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.EXPORT_MANAGE + "')")
    public CommonResult<Map<String, Object>> createDownloadTicket(@PathVariable("exportJobId") String exportJobId) {
        var job = requireDownloadableJob(Long.parseLong(exportJobId));
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
        var job = requireDownloadableJob(jobId);
        var consumption = deliveryPort.consumeOwnedDownloadTicket(ticket,
                SecurityFrameworkUtils.getLoginUserId(), "EXPORT_FILE", String.valueOf(jobId));
        if (consumption.getOutcome() != cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption.Outcome.CONSUMED_NOW
                || !"EXPORT_FILE".equals(consumption.getPurpose())
                || !String.valueOf(jobId).equals(consumption.getBizRef())) {
            throw new org.springframework.security.access.AccessDeniedException("下载票据无效");
        }
        byte[] content;
        try (java.io.InputStream in = storage.getObject(job.getFileAssetId())) {
            content = in.readNBytes(10 * 1024 * 1024 + 1);
            String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
            if (content.length > 10 * 1024 * 1024 || !digest.equals(job.getFileSha256())) {
                throw new org.springframework.security.access.AccessDeniedException("文件校验失败，请重新导出");
            }
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException | IllegalStateException e) {
            throw new org.springframework.security.access.AccessDeniedException("导出文件读取失败");
        }
        var headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.parseMediaType("text/csv; charset=UTF-8"));
        headers.setCacheControl(org.springframework.http.CacheControl.noStore());
        headers.set("Content-Disposition", "attachment; filename=export-" + jobId + ".csv");
        return org.springframework.http.ResponseEntity.ok().headers(headers).body(content);
    }

    @GetMapping("/audit-events")
    @Operation(summary = "审计事件分页查询：时间/类型/操作者/业务对象筛选，取证用；detail 原样下发")
    @PreAuthorize("@ss.hasPermission('" + PermissionConstants.AUDIT_READ + "')")
    public CommonResult<PageResult<Map<String, Object>>> getAuditEvents(
            @RequestParam(value = "eventType", required = false) String eventType,
            @RequestParam(value = "actorId", required = false) String actorId,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "bizId", required = false) String bizId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "pageNo", defaultValue = "1") Integer pageNo,
            @RequestParam(value = "pageSize", defaultValue = "20") Integer pageSize) {
        var jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        var where = new java.util.ArrayList<String>(java.util.List.of("TRUE"));
        var args = new java.util.ArrayList<Object>();
        if (eventType != null && !eventType.isBlank()) { where.add("event_type = ?"); args.add(eventType.trim()); }
        if (actorId != null && !actorId.isBlank()) { where.add("actor_id = ?"); args.add(actorId.trim()); }
        if (bizType != null && !bizType.isBlank()) { where.add("biz_type = ?"); args.add(bizType.trim()); }
        if (bizId != null && !bizId.isBlank()) { where.add("biz_id = ?"); args.add(bizId.trim()); }
        if (from != null && !from.isBlank()) { where.add("create_time >= ?"); args.add(java.sql.Timestamp.from(java.time.Instant.parse(from))); }
        if (to != null && !to.isBlank()) { where.add("create_time < ?"); args.add(java.sql.Timestamp.from(java.time.Instant.parse(to))); }
        String filter = " WHERE " + String.join(" AND ", where);
        long total = jdbc.queryForObject("SELECT count(*) FROM audit_event" + filter, Long.class, args.toArray());
        var params = new java.util.ArrayList<Object>(args);
        params.add(Math.min(pageSize, 100));
        params.add((long) Math.max(pageNo - 1, 0) * Math.min(pageSize, 100));
        var list = jdbc.queryForList(
                "SELECT id, event_type, actor_type, actor_id, action, biz_type, biz_id, result, "
                        + "detail::text, create_time FROM audit_event" + filter + " ORDER BY id DESC LIMIT ? OFFSET ?",
                params.toArray());
        return success(new PageResult<>(list, total));
    }

}
