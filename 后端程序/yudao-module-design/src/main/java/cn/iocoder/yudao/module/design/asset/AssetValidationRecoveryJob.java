package cn.iocoder.yudao.module.design.asset;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import javax.sql.DataSource;

@Component
public class AssetValidationRecoveryJob {
    private final JdbcTemplate jdbc;
    private final AssetService assets;
    @Value("${zhongshu.design.asset.validation-worker-enabled:true}") private boolean enabled;
    public AssetValidationRecoveryJob(DataSource ds, AssetService assets) { this.jdbc = new JdbcTemplate(ds); this.assets = assets; }
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void tick() {
        if (!enabled) return;
        for (var row : jdbc.queryForList("SELECT id, owner_user_id FROM asset WHERE deleted=FALSE AND "
                + "((upload_status='PENDING' AND moderation_retry_at<=now()) OR (upload_status='VALIDATING' AND update_time<now()-interval '10 minutes')) "
                + "ORDER BY COALESCE(moderation_retry_at,update_time),id LIMIT 10")) {
            try { assets.completeUpload(((Number)row.get("owner_user_id")).longValue(), ((Number)row.get("id")).longValue()); }
            catch (RuntimeException ignored) { /* Per-record isolation; retained state/age is observable by operations health. */ }
        }
    }
}
