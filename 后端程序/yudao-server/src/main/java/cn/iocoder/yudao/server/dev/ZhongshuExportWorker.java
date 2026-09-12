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
        boolean ledger = "POINT_LEDGER".equals(filters.get("jobType"));
        String[] columns = ledger ? new String[]{"id","user_id","type","delta","available_after","reserved_after","biz_type","biz_id","reason","create_time"}
                : new String[]{"id","event_type","actor_type","actor_id","action","biz_type","biz_id","result","create_time"};
        String[] header = ledger ? new String[]{"流水号","用户编号","类型","变动","变动后可用","变动后预留","业务类型","业务编号","备注","时间（UTC）"}
                : new String[]{"事件号","事件类型","操作者类型","操作者","动作","业务类型","业务编号","结果","时间（UTC）"};
        var out = new ByteArrayOutputStream(); out.writeBytes(new byte[]{(byte)0xef,(byte)0xbb,(byte)0xbf}); appendRow(out,(Object[])header);
        StringBuilder where = new StringBuilder(ledger ? "deleted=FALSE" : "1=1");
        var args = new ArrayList<Object>();
        where.append(" AND create_time<=?"); args.add(Timestamp.from(cutoff));
        if (filters.containsKey("userId")) { where.append(" AND user_id=?"); args.add(Long.parseLong(filters.get("userId").toString())); }
        if (filters.containsKey("type")) { where.append(ledger ? " AND type=?" : " AND event_type=?"); args.add(filters.get("type")); }
        for (var key : List.of("from","to")) if (filters.containsKey(key)) {
            where.append("from".equals(key) ? " AND create_time>=?" : " AND create_time<?"); args.add(Timestamp.from(Instant.parse(filters.get(key).toString())));
        }
        long cursor=0; int total=0;
        while (true) {
            renew(jobId,token);
            var pageArgs = new ArrayList<>(args); pageArgs.add(cursor); pageArgs.add(PAGE_SIZE);
            var page = jdbc.queryForList("SELECT "+String.join(",",columns)+" FROM "+(ledger?"design_point_ledger":"audit_event")+" WHERE "+where+" AND id>? ORDER BY id LIMIT ?",pageArgs.toArray());
            for (var row : page) {
                if (++total>MAX_ROWS) throw new ExportLimit("EXPORT_ROW_LIMIT");
                appendRow(out,Arrays.stream(columns).map(c->row.get(c)).toArray());
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
