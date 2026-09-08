package cn.iocoder.yudao.module.identity.session;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户会话（架构 §6.1 user_session）
 *
 * 合同：
 * - 数据库存 token 的 SHA-256，明文 token 仅签发时返回；
 * - 授权态实时判定：validate 联表 design_access_grant，授权被撤销立刻降级为受限会话；
 * - 账号被停用/关闭时所有会话即时失效。
 */
@Service
public class UserSessionService {

    public record AccessContext(long accountId, String appid, String openid, boolean restricted) {
    }

    public record IssuedTokens(long sessionId, String accessToken, String refreshToken, Instant expiresAt) {
    }

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final long ACCESS_TOKEN_TTL_SECONDS = 2 * 3600L;
    private static final long REFRESH_TOKEN_TTL_SECONDS = 30 * 24 * 3600L;

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    public UserSessionService(DataSource dataSource, PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    public IssuedTokens issue(long accountId, String appid, String openid, boolean restricted, String deviceDigest) {
        String accessToken = randomToken();
        String refreshToken = randomToken();
        Long sessionId = txTemplate.execute(status -> {
            long id = com.baomidou.mybatisplus.core.toolkit.IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO user_session (id, account_id, appid, openid, token_hash, refresh_token_hash, "
                            + "device_digest, restricted, expires_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, now() + (? * interval '1 second'))",
                    id, accountId, appid, openid,
                    sha256Hex(accessToken), sha256Hex(refreshToken), deviceDigest, restricted,
                    ACCESS_TOKEN_TTL_SECONDS);
            return id;
        });
        return new IssuedTokens(sessionId, accessToken, refreshToken,
                Instant.now().plusSeconds(ACCESS_TOKEN_TTL_SECONDS));
    }

    /** 校验 access token：未过期、未吊销、账号 ACTIVE；restricted 按当前授权实时判定 */
    public Optional<AccessContext> validateAccessToken(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        List<AccessContext> rows = jdbcTemplate.query(
                "SELECT s.account_id, s.appid, s.openid, s.revoked_at, s.expires_at, a.status AS account_status, "
                        + "(g.id IS NOT NULL) AS granted "
                        + "FROM user_session s "
                        + "JOIN account a ON a.id = s.account_id AND a.status = 'ACTIVE' AND a.deleted = FALSE "
                        + "LEFT JOIN design_access_grant g ON g.account_id = s.account_id "
                        + "  AND g.status = 'ACTIVE' AND g.deleted = FALSE "
                        + "WHERE s.token_hash = ? AND s.revoked_at IS NULL AND s.expires_at > now()",
                (rs, i) -> new AccessContext(rs.getLong("account_id"), rs.getString("appid"),
                        rs.getString("openid"), !rs.getBoolean("granted")),
                sha256Hex(token));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 刷新会话（审查 H3）：旧 access+refresh 吊销、新对签发；grant 状态实时重判 */
    public Optional<IssuedTokens> refresh(String refreshToken) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT s.id, s.account_id, s.appid, s.openid, s.revoked_at, a.status AS account_status "
                        + "FROM user_session s JOIN account a ON a.id = s.account_id "
                        + "AND a.status = 'ACTIVE' AND a.deleted = FALSE "
                        + "WHERE s.refresh_token_hash = ? AND s.revoked_at IS NULL "
                        + "AND s.expires_at > now()",
                sha256Hex(refreshToken));
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> row = rows.get(0);
        long accountId = ((Number) row.get("account_id")).longValue();
        String appid = (String) row.get("appid");
        String openid = (String) row.get("openid");
        return Optional.of(txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "UPDATE user_session SET revoked_at = now(), update_time = now() "
                            + "WHERE id = ?", row.get("id"));
            boolean granted = hasActiveGrant(accountId);
            return issue(accountId, appid, openid, !granted, null);
        }));
    }

    private boolean hasActiveGrant(long accountId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_access_grant WHERE account_id = ? AND status = 'ACTIVE' "
                        + "AND deleted = FALSE", Integer.class, accountId);
        return n != null && n > 0;
    }

    public boolean revokeByToken(String token) {
        return jdbcTemplate.update(
                "UPDATE user_session SET revoked_at = now(), update_time = now() "
                        + "WHERE token_hash = ? AND revoked_at IS NULL", sha256Hex(token)) == 1;
    }

    /** 吊销账号全部会话（解绑授权/关闭账号时调用） */
    public int revokeAllForAccount(long accountId) {
        return jdbcTemplate.update(
                "UPDATE user_session SET revoked_at = now(), update_time = now() "
                        + "WHERE account_id = ? AND revoked_at IS NULL", accountId);
    }

    public Optional<Instant> refreshExpiresAt(String refreshToken) {
        List<Timestamp> rows = jdbcTemplate.query(
                "SELECT expires_at FROM user_session WHERE refresh_token_hash = ? AND revoked_at IS NULL",
                (rs, i) -> rs.getTimestamp("expires_at"), sha256Hex(refreshToken));
        return rows.isEmpty() ? Optional.empty()
                : Optional.of(rows.get(0).toInstant().plusSeconds(REFRESH_TOKEN_TTL_SECONDS));
    }

    private static String randomToken() {
        byte[] raw = new byte[32];
        SECURE_RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

}
