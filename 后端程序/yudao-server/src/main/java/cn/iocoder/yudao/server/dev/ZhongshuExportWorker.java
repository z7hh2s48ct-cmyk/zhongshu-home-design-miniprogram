package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 异步导出文件生成 worker（审查 C10）：export_job 只有建单与票据链路，
 * 本 worker 领取 PENDING 任务 → 按类型生成 CSV → 写对象存储 → completeExportJob 固化。
 *
 * 与驱动器同层（server 聚合模块，跨业务库取数）；独立开关
 * zhongshu.design.export-worker-enabled（默认 false，zsdev 打开，生产按需开启）。
 * 支持 POINT_LEDGER / AUDIT_EVENTS 两类；未知类型显式 FAILED，不留悬挂任务。
 */
@Slf4j
@Component
public class ZhongshuExportWorker {

    private static final int BATCH = 5;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final DeliveryPort deliveryPort;
    private final ObjectStoragePort storage;

    @Value("${zhongshu.design.export-worker-enabled:false}")
    private boolean enabled;

    public ZhongshuExportWorker(DataSource dataSource, DeliveryPort deliveryPort, ObjectStoragePort storage) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.deliveryPort = deliveryPort;
        this.storage = storage;
    }

    /** 每 15 秒一轮；PENDING→RUNNING 用 CAS 领取，多实例部署不重复产出 */
    @Scheduled(fixedDelay = 15_000, initialDelay = 30_000)
    public void tick() {
        if (!enabled) {
            return;
        }
        try {
            List<Map<String, Object>> pending = jdbcTemplate.queryForList(
                    "SELECT id, job_type, filter_snapshot::text AS filter_text FROM export_job "
                            + "WHERE status = 'PENDING' ORDER BY id LIMIT " + BATCH);
            for (Map<String, Object> job : pending) {
                long jobId = ((Number) job.get("id")).longValue();
                // CAS 领取：并发 worker 只有一方拿到 1 行
                int claimed = jdbcTemplate.update(
                        "UPDATE export_job SET status = 'RUNNING', update_time = now() "
                                + "WHERE id = ? AND status = 'PENDING'", jobId);
                if (claimed != 1) {
                    continue;
                }
                process(jobId, (String) job.get("job_type"));
            }
        } catch (Exception e) {
            log.warn("[export-tick][导出轮失败，下轮重试: {}]", e.getMessage());
        }
    }

    private void process(long jobId, String jobType) {
        try {
            byte[] csv = switch (jobType) {
                case "POINT_LEDGER" -> pointLedgerCsv();
                case "AUDIT_EVENTS" -> auditEventsCsv();
                default -> throw new IllegalArgumentException("不支持的导出类型: " + jobType);
            };
            String objectKey = "exports/" + jobId + ".csv";
            storage.putObject(objectKey, csv);
            deliveryPort.completeExportJob(jobId, objectKey, sha256Hex(csv), 86_400);
            log.info("[export-worker][job={} {} 已生成 {} 字节]", jobId, jobType, csv.length);
        } catch (Exception e) {
            log.warn("[export-worker][job={} 生成失败: {}]", jobId, e.getMessage());
            deliveryPort.failExportJob(jobId, e.getMessage() == null ? "unknown" : e.getMessage());
        }
    }

    /** 点数流水全量导出（运营对账口径，与 /point-ledger 同表） */
    private byte[] pointLedgerCsv() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, user_id, type, delta, available_after, reserved_after, biz_type, biz_id, "
                        + "reason, create_time FROM design_point_ledger WHERE deleted = FALSE ORDER BY id");
        String[] header = {"流水号", "用户编号", "类型", "变动", "变动后可用", "变动后预留", "业务类型", "业务编号", "备注", "时间"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}); // UTF-8 BOM：Excel 直开不乱码
        appendRow(out, (Object[]) header);
        for (Map<String, Object> row : rows) {
            appendRow(out, row.get("id"), row.get("user_id"), row.get("type"), row.get("delta"),
                    row.get("available_after"), row.get("reserved_after"), row.get("biz_type"),
                    row.get("biz_id"), row.get("reason"), formatTime(row.get("create_time")));
        }
        return out.toByteArray();
    }

    private byte[] auditEventsCsv() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, event_type, actor_type, actor_id, action, biz_type, biz_id, result, create_time "
                        + "FROM audit_event ORDER BY id");
        String[] header = {"事件号", "事件类型", "操作者类型", "操作者", "动作", "业务类型", "业务编号", "结果", "时间"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        appendRow(out, (Object[]) header);
        for (Map<String, Object> row : rows) {
            appendRow(out, row.get("id"), row.get("event_type"), row.get("actor_type"),
                    row.get("actor_id"), row.get("action"), row.get("biz_type"), row.get("biz_id"),
                    row.get("result"), formatTime(row.get("create_time")));
        }
        return out.toByteArray();
    }

    /** 极简 CSV 组装：值内引号/逗号/换行按 RFC 4180 转义 */
    private void appendRow(ByteArrayOutputStream out, Object... cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                line.append(',');
            }
            String value = cells[i] == null ? "" : String.valueOf(cells[i]);
            if (value.contains("\"") || value.contains(",") || value.contains("\n") || value.contains("\r")) {
                value = '"' + value.replace("\"", "\"\"") + '"';
            }
            line.append(value);
        }
        line.append('\r').append('\n');
        out.writeBytes(line.toString().getBytes(StandardCharsets.UTF_8));
    }

    private String formatTime(Object value) {
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().format(TS);
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.format(TS);
        }
        if (value instanceof java.time.Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(TS);
        }
        return value == null ? "" : String.valueOf(value);
    }

    private String sha256Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

}
