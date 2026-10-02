package cn.iocoder.yudao.server.dev;

import cn.iocoder.yudao.module.design.asset.ObjectStoragePort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.ExportFilters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Bounded keyset export with crash recovery and fenced publication. */
@Slf4j
@Component
public class ZhongshuExportWorker {
    private static final int PAGE_SIZE = 500, MAX_ROWS = 10000, MAX_BYTES = 10 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final ObjectStoragePort storage;
    @Value("${zhongshu.design.export-worker-enabled:false}") private boolean enabled;
    public ZhongshuExportWorker(DataSource dataSource, DeliveryPort deliveryPort, ObjectStoragePort storage) {
        this.jdbc = new JdbcTemplate(dataSource); this.jdbc.setQueryTimeout(15); this.storage = storage;
    }
    @Scheduled(fixedDelay = 15000, initialDelay = 30000)
    public void tick() {
        if (!enabled) return;
        try {
            // 先收口已请求取消的任务（F-5）：置 FAILED/EXPORT_CANCELLED，与业务失败同一展示通道
            jdbc.update("UPDATE export_job SET status='FAILED',error='EXPORT_CANCELLED',lease_token=NULL,lease_expires_at=NULL,update_time=now() "
                    + "WHERE cancel_requested AND status IN ('PENDING','RUNNING')");
            var pending = jdbc.queryForList("SELECT id FROM export_job WHERE status='PENDING' "
                    + "OR (status='RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at < now())) ORDER BY id LIMIT 5", Long.class);
            for (Long id : pending) processClaim(id);
        } catch (Exception e) { log.warn("[export-worker] tick failed: {}", e.getClass().getSimpleName()); }
    }
    void processClaim(long id) {
        String token = UUID.randomUUID().toString();
        try {
            var rows = jdbc.queryForList("UPDATE export_job SET status='RUNNING', lease_token=?, lease_expires_at=now()+interval '120 seconds', "
                    + "attempts=attempts+1, update_time=now() WHERE id=? AND (status='PENDING' "
                    + "OR (status='RUNNING' AND (lease_expires_at IS NULL OR lease_expires_at<now()))) RETURNING job_type, filter_snapshot::text, create_time, attempts", token,id);
            if (rows.isEmpty()) return;
            var row = rows.get(0);
            if (((Number)row.get("attempts")).intValue() > 5) throw new ExportLimit("EXPORT_RETRY_LIMIT");
            var raw = new com.fasterxml.jackson.databind.ObjectMapper().readValue((String)row.get("filter_snapshot"), new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
            raw = new HashMap<>(raw); raw.put("jobType", row.get("job_type"));
            var filter = ExportFilters.validate(raw);
            byte[] csv = csv(id,token,filter,((Timestamp)row.get("create_time")).toInstant());
            renew(id,token);
            String objectKey = "exports/"+id+"/"+token+".csv";
            storage.putObject(objectKey,csv);
            jdbc.update("UPDATE export_job SET status='COMPLETED',file_asset_id=?,file_sha256=?,expires_at=now()+interval '1 day', "
                    + "lease_token=NULL,lease_expires_at=NULL,error=NULL,update_time=now() WHERE id=? AND status='RUNNING' AND lease_token=? AND lease_expires_at>now()",
                    objectKey, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(csv)),id,token);
        } catch (Exception e) {
            String code = e instanceof ExportLimit ? e.getMessage() : "EXPORT_FAILED";
            jdbc.update("UPDATE export_job SET status='FAILED',error=?,lease_token=NULL,lease_expires_at=NULL,update_time=now() "
                    + "WHERE id=? AND status='RUNNING' AND lease_token=?",code,id,token);
            log.warn("[export-worker] job={} failureType={}",id,e.getClass().getSimpleName());
        }
    }
    private void renew(long id,String token) {
        if (jdbc.update("UPDATE export_job SET lease_expires_at=now()+interval '120 seconds' WHERE id=? "
                + "AND status='RUNNING' AND lease_token=? AND lease_expires_at>now()",id,token)!=1) throw new ExportLimit("EXPORT_LEASE_LOST");
    }
    private byte[] csv(long jobId,String token,Map<String,Object> filters,Instant cutoff) {
        // 2026-10-02 扩充：批次交付记录（含每批次已兑换数子查询）与 C端用户清单（余额/授权态子查询）
        String jobType = String.valueOf(filters.get("jobType"));
        String table;
        String[] columns;
        String[] header;
        boolean hasDeleted = true;
        String typeColumn = "type";
        switch (jobType) {
            case "POINT_LEDGER" -> {
                table = "design_point_ledger";
                columns = new String[]{"id","user_id","type","delta","available_after","reserved_after","biz_type","biz_id","reason","create_time"};
                header = new String[]{"流水号","用户编号","类型","变动","变动后可用","变动后预留","业务类型","业务编号","备注","时间（UTC）"};
            }
            case "AUDIT_EVENTS" -> {
                table = "audit_event";
                columns = new String[]{"id","event_type","actor_type","actor_id","action","biz_type","biz_id","result","create_time"};
                header = new String[]{"事件号","事件类型","操作者类型","操作者","动作","业务类型","业务编号","结果","时间（UTC）"};
                hasDeleted = false;
                typeColumn = "event_type";
            }
            case "ACCESS_CODE_BATCHES" -> {
                table = "design_access_code_batch";
                columns = new String[]{"id","quantity","delivery_mode","exposed_count","issued_by","purpose_note","expires_at","create_time"};
                header = new String[]{"批次号","数量","交付方式","明文暴露数","发行人","用途备注","有效期至","创建时间（UTC）"};
            }
            default -> {
                table = "account";
                columns = new String[]{"id","nickname","status","create_time"};
                header = new String[]{"用户编号","昵称","状态","注册时间（UTC）"};
                typeColumn = "status";
            }
        }
        var out = new ByteArrayOutputStream();
        StringBuilder where = new StringBuilder(hasDeleted ? "deleted=FALSE" : "1=1");
        var args = new ArrayList<Object>();
        where.append(" AND create_time<=?"); args.add(Timestamp.from(cutoff));
        if (filters.containsKey("userId")) { where.append(" AND user_id=?"); args.add(Long.parseLong(filters.get("userId").toString())); }
        if (filters.containsKey("type")) { where.append(" AND ").append(typeColumn).append("=?"); args.add(filters.get("type")); }
        for (var key : List.of("from","to")) if (filters.containsKey(key)) {
            where.append("from".equals(key) ? " AND create_time>=?" : " AND create_time<?"); args.add(Timestamp.from(Instant.parse(filters.get(key).toString())));
        }
        String selectColumns = String.join(",",columns);
        String[] keys = columns;
        if ("ACCESS_CODE_BATCHES".equals(jobType)) {
            selectColumns = selectColumns + ",(SELECT count(*) FROM access_code_redemption r "
                    + "WHERE r.code_id IN (SELECT c.id FROM design_access_code c WHERE c.batch_id = design_access_code_batch.id)) AS redeemed_count";
            keys = new String[]{columns[0],columns[1],columns[2],columns[3],columns[4],columns[5],columns[6],columns[7],"redeemed_count"};
            header = new String[]{"批次号","数量","交付方式","明文暴露数","发行人","用途备注","有效期至","创建时间（UTC）","已兑换数"};
        } else if ("ACCOUNTS".equals(jobType)) {
            selectColumns = selectColumns
                    + ",COALESCE((SELECT available_points FROM design_point_account pa WHERE pa.user_id = account.id AND pa.deleted = FALSE), 0) AS available_points"
                    + ",COALESCE((SELECT g.status FROM design_access_grant g WHERE g.account_id = account.id AND g.status = 'ACTIVE' AND g.deleted = FALSE), 'NONE') AS grant_status";
            keys = new String[]{columns[0],columns[1],columns[2],columns[3],"available_points","grant_status"};
            header = new String[]{"用户编号","昵称","状态","注册时间（UTC）","可用点数","授权状态"};
        }
        final String[] rowKeys = keys;
        // E-14：追加北京时间列（页面对账口径），UTC ISO 列保留供程序消费
        selectColumns = selectColumns + ",to_char(create_time AT TIME ZONE 'Asia/Shanghai','YYYY-MM-DD HH24:MI:SS') AS create_time_bj";
        var allKeys = new ArrayList<String>(Arrays.asList(rowKeys));
        allKeys.add("create_time_bj");
        var allHeader = new ArrayList<String>(Arrays.asList(header));
        allHeader.add("北京时间");
        // 表头在派生列计算完成后写入，保证批次/用户清单带上附加列
        out.writeBytes(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf}); appendRow(out,allHeader.toArray());
        final var finalKeys = allKeys;
        long cursor=0; int total=0;
        while (true) {
            renew(jobId,token);
            var pageArgs = new ArrayList<>(args); pageArgs.add(cursor); pageArgs.add(PAGE_SIZE);
            var page = jdbc.queryForList("SELECT "+selectColumns+" FROM "+table+" WHERE "+where+" AND id>? ORDER BY id LIMIT ?",pageArgs.toArray());
            for (var row : page) {
                if (++total>MAX_ROWS) throw new ExportLimit("EXPORT_ROW_LIMIT");
                appendRow(out,finalKeys.stream().map(c->row.get(c)).toArray());
                cursor=((Number)row.get("id")).longValue();
            }
            if (page.size()<PAGE_SIZE) return out.toByteArray();
        }
    }
    static void appendRow(ByteArrayOutputStream out,Object... cells) {
        var line = new StringBuilder();
        for (int i=0;i<cells.length;i++) {
            if(i>0) line.append(',');
            String value = cells[i] instanceof Timestamp time ? time.toInstant().toString() : cells[i]==null?"":cells[i].toString();
            String trimmed=value.stripLeading();
            if (!(cells[i] instanceof Number) && !trimmed.isEmpty()
                    && ("=+-@".indexOf(trimmed.charAt(0))>=0 || value.charAt(0)=='\t' || value.charAt(0)=='\r' || value.charAt(0)=='\n')) value="'"+value;
            line.append('"').append(value.replace("\"","\"\"")).append('"');
        }
        byte[] bytes=line.append("\r\n").toString().getBytes(StandardCharsets.UTF_8);
        if ((long)out.size()+bytes.length>MAX_BYTES) throw new ExportLimit("EXPORT_SIZE_LIMIT");
        out.writeBytes(bytes);
    }
    private static class ExportLimit extends RuntimeException { ExportLimit(String code) { super(code); } }
}
