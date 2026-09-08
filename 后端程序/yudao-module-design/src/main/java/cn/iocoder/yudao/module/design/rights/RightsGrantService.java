package cn.iocoder.yudao.module.design.rights;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;

/**
 * 版本化权利授权真源（架构 §6.9 asset_rights_grant）
 *
 * 合同：
 * - 公开展示（PUBLIC_DISPLAY）与生成参考（GENERATION_REFERENCE）是两种独立授权，
 *   后者不能随审核通过自动获得（P7A 落地该规则，本服务只管授权事实）；
 * - 只有权利人（资产所有者）能授予；撤回立即生效：读链路与任务准入按当前状态实时判定；
 * - 授权有 rights_version：任务/发布引用时冻结版本快照，撤回后历史快照可审计。
 */
@Slf4j
@Service
public class RightsGrantService {

    public record GrantRow(long id, long assetId, long grantorUserId, String rightsHolder, String scope,
                           String territories, Instant effectiveAt, Instant expiresAt,
                           String status, Instant withdrawnAt, long rightsVersion) {
    }

    private final JdbcTemplate jdbcTemplate;

    public RightsGrantService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 授予（grantor 必须是资产所有者） */
    public long createGrant(long grantorUserId, long assetId, String scope, String rightsHolder,
                            String territories, String purposes,
                            Instant effectiveAt, Instant expiresAt) {
        Long owner = jdbcTemplate.queryForObject(
                "SELECT owner_user_id FROM asset WHERE id = ? AND deleted = FALSE", Long.class, assetId);
        if (owner == null || owner != grantorUserId) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO asset_rights_grant (id, asset_id, grantor_user_id, rights_holder, scope, "
                        + "territories, purposes, effective_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, assetId, grantorUserId, rightsHolder, scope,
                territories == null ? "*" : territories,
                purposes == null ? "*" : purposes,
                Timestamp.from(effectiveAt), expiresAt == null ? null : Timestamp.from(expiresAt));
        log.info("[createGrant][grant={} asset={} scope={} by={}]", id, assetId, scope, grantorUserId);
        return id;
    }

    /** 撤回：立即生效（rights_version+1 供快照审计） */
    public boolean withdraw(long grantId, String withdrawnBy) {
        boolean ok = jdbcTemplate.update(
                "UPDATE asset_rights_grant SET status = 'WITHDRAWN', withdrawn_at = now(), "
                        + "rights_version = rights_version + 1, update_time = now() "
                        + "WHERE id = ? AND status = 'ACTIVE'", grantId) == 1;
        if (ok) {
            log.info("[withdraw][grant={} by={}]", grantId, withdrawnBy);
        }
        return ok;
    }

    /** 当前是否存在有效授权（实时判定；撤回/过期立即失效） */
    public boolean hasEffectiveGrant(long assetId, String scope) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM asset_rights_grant WHERE asset_id = ? AND scope = ? "
                        + "AND status = 'ACTIVE' AND effective_at <= now() "
                        + "AND (expires_at IS NULL OR expires_at > now()) AND deleted = FALSE",
                Integer.class, assetId, scope);
        return n != null && n > 0;
    }

    public Optional<GrantRow> findGrant(long grantId) {
        List<GrantRow> rows = jdbcTemplate.query(
                "SELECT id, asset_id, grantor_user_id, rights_holder, scope, territories, effective_at, "
                        + "expires_at, status, withdrawn_at, rights_version "
                        + "FROM asset_rights_grant WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new GrantRow(rs.getLong("id"), rs.getLong("asset_id"),
                        rs.getLong("grantor_user_id"), rs.getString("rights_holder"), rs.getString("scope"),
                        rs.getString("territories"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status"),
                        rs.getTimestamp("withdrawn_at") == null ? null : rs.getTimestamp("withdrawn_at").toInstant(),
                        rs.getLong("rights_version")),
                grantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

}
