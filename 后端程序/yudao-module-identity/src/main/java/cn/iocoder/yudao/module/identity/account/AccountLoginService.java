package cn.iocoder.yudao.module.identity.account;

import cn.iocoder.yudao.module.identity.session.UserSessionService;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityException;
import cn.iocoder.yudao.module.identity.wechat.WechatIdentityPort;
import cn.iocoder.yudao.module.identity.wechat.WechatLoginFailure;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信登录：login code → openid → Account（+受限/正式会话）
 *
 * 合同：
 * - 未获准账号得到受限会话（restricted），只允许查准入状态、协议、兑换授权码；
 * - 同一 (appid, openid) 唯一，重复登录幂等返回同一账号；
 * - 是否获准以 design_access_grant 的当前状态实时判定。
 */
@Slf4j
@Service
public class AccountLoginService {

    public record LoginResult(long accountId, String appid, String openid,
                              String accessToken, String refreshToken, Instant expiresAt,
                              boolean restricted) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final WechatIdentityPort wechatIdentityPort;

    private final UserSessionService sessionService;

    public AccountLoginService(DataSource dataSource, PlatformTransactionManager transactionManager,
                               WechatIdentityPort wechatIdentityPort, UserSessionService sessionService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.wechatIdentityPort = wechatIdentityPort;
        this.sessionService = sessionService;
    }

    public LoginResult login(String appid, String loginCode, String deviceDigest) {
        // T13-05：code2session 失败按四类翻译为不同错误码（无效/已使用 code、微信服务异常、配置错误），而非一律当作通用 500。
        // codeToSession 先于任何 DB 访问，抛异常时不触库；一次性 code 绝不重试，失败即抛出由客户端重新 wx.login 取新 code。
        WechatIdentityPort.WechatSession wxSession;
        try {
            wxSession = wechatIdentityPort.codeToSession(appid, loginCode);
        } catch (WechatIdentityException e) {
            WechatLoginFailure failure = e.getFailure();
            // 只记录失败分类与微信数值 errcode（均为安全值，不含 secret/code/URL）；配置错误以 ERROR 级告警运维，其余 WARN。
            if (failure == WechatLoginFailure.CONFIG_ERROR) {
                log.error("[login][微信身份配置错误 kind={} errcode={}]", failure, e.getErrcode());
            } else {
                log.warn("[login][微信登录失败 kind={} errcode={}]", failure, e.getErrcode());
            }
            throw failure.toServiceException();
        }
        long accountId = resolveAccount(appid, wxSession.openid(), wxSession.unionid());
        boolean granted = hasActiveGrant(accountId);
        UserSessionService.IssuedTokens tokens = sessionService.issue(
                accountId, appid, wxSession.openid(), !granted, deviceDigest);
        return new LoginResult(accountId, appid, wxSession.openid(),
                tokens.accessToken(), tokens.refreshToken(), tokens.expiresAt(), !granted);
    }

    /** 身份已被其他事务绑定（并发首登）：整体回滚后复用既有账号 */
    private static class IdentityConflictException extends RuntimeException {
    }

    /** 查找或创建 (appid, openid) 对应账号；同一微信身份幂等，账号与身份同事务创建 */
    public long resolveAccount(String appid, String openid, String unionid) {
        Optional<Long> existing = findAccountId(appid, openid);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            Long created = txTemplate.execute(status -> {
                long accountId = IdWorker.getId();
                jdbcTemplate.update("INSERT INTO account (id) VALUES (?)", accountId);
                jdbcTemplate.update(
                        "INSERT INTO wechat_identity (id, appid, openid, unionid, account_id) "
                                + "VALUES (?, ?, ?, ?, ?)",
                        IdWorker.getId(), appid, openid, unionid, accountId);
                log.info("[resolveAccount][新建账号 {} 绑定 {}/{}]", accountId, appid, openid);
                return accountId;
            });
            return created;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 并发首登：身份唯一键 (appid, openid) 冲突 → 本事务（含新账号行）已回滚，复用既有账号
            return findAccountId(appid, openid).orElseThrow(() -> e);
        }
    }

    public boolean hasActiveGrant(long accountId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_access_grant WHERE account_id = ? AND status = 'ACTIVE' "
                        + "AND deleted = FALSE", Integer.class, accountId);
        return n != null && n > 0;
    }

    public Optional<Long> findAccountId(String appid, String openid) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT account_id FROM wechat_identity WHERE appid = ? AND openid = ? AND deleted = FALSE",
                (rs, i) -> rs.getLong("account_id"), appid, openid);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ========== "我的"页基础信息 ==========

    public record AccountProfile(long accountId, String status, String nickname, String avatar) {
    }

    public Optional<AccountProfile> findProfile(long accountId) {
        List<AccountProfile> rows = jdbcTemplate.query(
                "SELECT id, status, nickname, avatar FROM account WHERE id = ? AND deleted = FALSE",
                (rs, i) -> new AccountProfile(rs.getLong("id"), rs.getString("status"),
                        rs.getString("nickname"), rs.getString("avatar")),
                accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * 更新偏好设置。字段白名单由服务端固定，避免客户端把任意键写进 preferences；
     * 白名单外的键直接丢弃而不是报错，便于新旧客户端并存。
     */
    private static final java.util.Set<String> PREFERENCE_KEYS =
            java.util.Set.of("messagePush", "defaultRegionCode", "defaultStyleCode");

    public boolean updatePreferences(long accountId, Map<String, Object> preferences) {
        Map<String, Object> filtered = new java.util.LinkedHashMap<>();
        if (preferences != null) {
            preferences.forEach((k, v) -> {
                if (PREFERENCE_KEYS.contains(k)) {
                    filtered.put(k, v);
                }
            });
        }
        return jdbcTemplate.update(
                "UPDATE account SET preferences = CAST(? AS jsonb), update_time = now() "
                        + "WHERE id = ? AND deleted = FALSE",
                toJson(filtered), accountId) == 1;
    }

    /** 偏好值为标量（布尔/数字/字符串），手工序列化足够且不引入 JSON 依赖 */
    private String toJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(String.valueOf(v).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }

}
