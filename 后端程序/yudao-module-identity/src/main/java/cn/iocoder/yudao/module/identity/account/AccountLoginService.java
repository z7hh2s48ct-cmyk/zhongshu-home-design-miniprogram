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
import org.springframework.transaction.TransactionDefinition;
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

    /**
     * 账号解析专用事务模板，传播行为固定为 {@code NESTED}（JDBC savepoint）：find-or-create 身份在<b>调用方事务内的
     * 保存点</b>中完成。唯一键冲突只回滚到保存点、清除 PostgreSQL 的事务中止状态（SQLSTATE 25P02），随后重查即可复用
     * 并发赢家已提交的账号。保存点复用<b>同一物理连接</b>——不像 {@code REQUIRES_NEW} 那样再占用一个连接（在 Druid
     * {@code max-active} 上限下，并发兑换各持外层连接再等内层连接会导致连接池饥饿死锁）；且子事务随调用方外层事务一起
     * 提交/回滚，保持“无效兑换不落账号”的既有语义。详见 {@link #resolveAccount}。
     */
    private final TransactionTemplate resolveAccountTxTemplate;

    private final WechatIdentityPort wechatIdentityPort;

    private final UserSessionService sessionService;

    public AccountLoginService(DataSource dataSource, PlatformTransactionManager transactionManager,
                               WechatIdentityPort wechatIdentityPort, UserSessionService sessionService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.resolveAccountTxTemplate = new TransactionTemplate(transactionManager);
        // NESTED（savepoint）：唯一键冲突只回滚到保存点、不污染调用方外层事务，令 catch 后的重查能在同一连接上读到
        // 并发赢家已提交的账号；相比 REQUIRES_NEW 不再占用第二个连接（杜绝连接池饥饿），新建账号也随外层事务一起回滚。
        // 生产用的 DataSourceTransactionManager 其 nestedTransactionAllowed 默认即为 true（无参构造器已开启），故 NESTED
        // 开箱可用；本服务不从内部改动容器共享的事务管理器，以免对其他 NESTED 调用方产生副作用——任何此类配置属配置层职责。
        this.resolveAccountTxTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
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

    /**
     * 查找或创建 (appid, openid) 对应账号；同一微信身份幂等，账号与身份在<b>保存点子事务</b>内同生共死。
     *
     * <p><b>并发首登恢复（T13-06）</b>：多路并发解析同一新身份时只有一路 INSERT 成功；其余各路在唯一键
     * {@code uk_wechat_identity_appid_openid} 上冲突抛 {@link org.springframework.dao.DuplicateKeyException}，
     * 其保存点子事务回滚到 savepoint（新建的账号行一并撤销，绝不留孤儿），并清除 PostgreSQL 的事务中止状态，随后重查
     * 即读到赢家已提交的账号。
     *
     * <p><b>为何用 {@code NESTED} 而非 {@code REQUIRED}/{@code REQUIRES_NEW}</b>：本方法会被
     * {@code AccessCodeRedemptionService#redeem} 在其外层事务内调用。
     * <ul>
     *   <li>{@code REQUIRED}（加入外层事务）：唯一键冲突会中止整个共享物理事务（PostgreSQL SQLSTATE 25P02
     *       “current transaction is aborted”），catch 后的重查立即失败、外层兑换事务也无法提交——同一新微信并发兑换
     *       将有一路以上报内部错误。</li>
     *   <li>{@code REQUIRES_NEW}（独立物理事务）：虽隔离了冲突，但要占用<b>第二个连接</b>；当并发兑换各持外层连接
     *       （及码行锁）再等内层连接时，在 Druid {@code max-active} 上限下会连接池饥饿死锁。且独立事务会<b>提前提交</b>
     *       新账号，即使外层兑换随后因 {@code ACCESS_CODE_ALREADY_CONSUMED} 回滚，账号仍残留（破坏“无效兑换不落账号”）。</li>
     *   <li>{@code NESTED}（savepoint）：复用同一连接（无池饥饿），冲突只回滚到 savepoint 并清除中止状态（重查得以成功），
     *       且新账号随外层事务一起提交/回滚（保持原语义）——三者兼顾，是这里的正确选择。</li>
     * </ul>
     * 经 login（无外层事务）调用时，{@code NESTED} 等价于新开事务，行为与原实现一致。
     *
     * <p><b>隔离级别契约</b>：并发恢复依赖外层事务为 PostgreSQL 默认的 {@code READ COMMITTED}（重查取新快照，能看到
     * 赢家已提交的账号）；若调用方显式使用 {@code REPEATABLE READ} 等快照隔离，重查可能停留在旧快照而重抛冲突，此时应由
     * 调用方整体重试。当前所有调用方（login / redeem）均为默认 {@code READ COMMITTED}。
     */
    public long resolveAccount(String appid, String openid, String unionid) {
        Optional<Long> existing = findAccountId(appid, openid);
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            Long created = resolveAccountTxTemplate.execute(status -> {
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
            // 并发首登：唯一键冲突已回滚到 savepoint（含新账号行）并清除事务中止状态；重查复用赢家账号
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
                        + "WHERE id = ? AND status='ACTIVE' AND deleted = FALSE",
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
