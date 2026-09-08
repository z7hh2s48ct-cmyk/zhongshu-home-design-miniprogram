package cn.iocoder.yudao.module.identity.accesscode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 访问授权（design_access_grant）：查询与后台解绑
 *
 * 合同：解绑只撤销 grant（旧授权码保持 CONSUMED 永不复活）；会话授权态由 UserSessionService 实时联表判定。
 */
@Service
public class AccessGrantService {

    public record GrantRow(long id, long accountId, String status, Instant grantedAt,
                           Instant revokedAt, String revokedBy) {
    }

    private final JdbcTemplate jdbcTemplate;

    public AccessGrantService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public Optional<GrantRow> findGrant(long grantId) {
        List<GrantRow> rows = jdbcTemplate.query(
                "SELECT id, account_id, status, granted_at, revoked_at, revoked_by "
                        + "FROM design_access_grant WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new GrantRow(rs.getLong("id"), rs.getLong("account_id"), rs.getString("status"),
                        rs.getTimestamp("granted_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant(),
                        rs.getString("revoked_by")),
                grantId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<GrantRow> findActiveByAccount(long accountId) {
        List<GrantRow> rows = jdbcTemplate.query(
                "SELECT id, account_id, status, granted_at, revoked_at, revoked_by "
                        + "FROM design_access_grant WHERE account_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                (rs, i) -> new GrantRow(rs.getLong("id"), rs.getLong("account_id"), rs.getString("status"),
                        rs.getTimestamp("granted_at").toInstant(), null, null),
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 解绑：仅 ACTIVE 可撤销；返回 false 表示不存在有效授权 */
    public boolean revoke(long grantId, String revokedBy) {
        return jdbcTemplate.update(
                "UPDATE design_access_grant SET status = 'REVOKED', revoked_at = now(), revoked_by = ?, "
                        + "update_time = now() WHERE id = ? AND status = 'ACTIVE'",
                revokedBy, grantId) == 1;
    }

}
