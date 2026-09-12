package cn.iocoder.yudao.server.runtime;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;
import java.util.LinkedHashMap;

/** Counts only; no identities, payloads, credential values or signed URLs in health output. */
@Component("businessBacklog")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="spring.flyway.enabled",havingValue="true")
public class BusinessBacklogHealth implements HealthIndicator {
    private final JdbcTemplate jdbc;
    public BusinessBacklogHealth(DataSource ds) { jdbc=new JdbcTemplate(ds);jdbc.setQueryTimeout(3); }
    public Health health() {
        try {
            var counts=new LinkedHashMap<String,Long>();
            counts.put("aiOverdue",count("SELECT count(*) FROM ai_job WHERE status IN ('QUEUED','RUNNING','VALIDATING','CANCEL_REQUESTED') AND deleted=FALSE AND create_time<now()-interval '35 minutes'"));
            counts.put("deadEvents",count("SELECT count(*) FROM outbox_event WHERE status='DEAD'"));
            counts.put("staleEvents",count("SELECT count(*) FROM outbox_event WHERE status='PENDING' AND create_time<now()-interval '15 minutes'"));
            counts.put("staleExports",count("SELECT count(*) FROM export_job WHERE status IN ('PENDING','RUNNING') AND create_time<now()-interval '15 minutes'"));
            counts.put("staleAssetValidation",count("SELECT count(*) FROM asset WHERE upload_status='VALIDATING' AND update_time<now()-interval '15 minutes' AND deleted=FALSE"));
            return (counts.values().stream().anyMatch(n->n>0)?Health.down():Health.up()).withDetails(counts).build();
        } catch(RuntimeException e) { return Health.down().withDetail("reason","BACKLOG_QUERY_FAILED").build(); }
    }
    private long count(String sql) { return jdbc.queryForObject(sql,Long.class); }
}
