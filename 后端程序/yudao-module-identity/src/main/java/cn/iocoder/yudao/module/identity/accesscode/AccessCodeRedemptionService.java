package cn.iocoder.yudao.module.identity.accesscode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.identity.account.AccountLoginService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_ALREADY_CONSUMED;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_DISABLED;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_EXPIRED;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_INVALID;

/**
 * 授权码兑换（架构 §8.1 事务链）
 *
 * 合同：
 * - 同一事务完成：锁码校验、兑换事实、AccessGrant、码 CONSUMED；进程在任何点位崩溃整体回滚，重试可成功且只消费一次；
 * - 幂等键 = appid + openid + access_code_id：同一微信重复兑换同一码返回既有授权（不报错）；
 *   其他微信身份兑换已消费码报 ACCESS_CODE_ALREADY_CONSUMED；
 * - 后台解绑只撤销 grant，码保持 CONSUMED 永不复活。
 */
@Slf4j
@Service
public class AccessCodeRedemptionService {

    public record RedemptionResult(long accountId, long grantId, boolean firstTimeGranted,
                                   String codeMask, Instant consumedAt) {
    }

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final AccessCodeCipher cipher;

    private final AccountLoginService accountLoginService;

    public AccessCodeRedemptionService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                       AccessCodeCipher cipher, AccountLoginService accountLoginService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.cipher = cipher;
        this.accountLoginService = accountLoginService;
    }

    public RedemptionResult redeem(String appid, String openid, String unionid, String plaintextCode) {
        String codeHash = cipher.hash(plaintextCode);
        try {
            return txTemplate.execute(status -> redeemInTx(appid, openid, unionid, codeHash));
        } catch (RedeemIdempotentReplayException replay) {
            // 同一微信重复兑换同一码：幂等返回既有结果
            return replay.previous;
        }
    }

    private static class RedeemIdempotentReplayException extends RuntimeException {
        final RedemptionResult previous;

        RedeemIdempotentReplayException(RedemptionResult previous) {
            super("同一微信重复兑换同一码，幂等返回");
            this.previous = previous;
        }
    }

    /** 须在事务内调用（持有授权码行锁） */
    private RedemptionResult redeemInTx(String appid, String openid, String unionid, String codeHash) {
        List<Map<String, Object>> codeRows = jdbcTemplate.queryForList(
                "SELECT id, status, code_mask, expires_at, consumed_at FROM design_access_code "
                        + "WHERE code_hash = ? AND deleted = FALSE FOR UPDATE",
                codeHash);
        if (codeRows.isEmpty()) {
            throw exception(ACCESS_CODE_INVALID);
        }
        Map<String, Object> code = codeRows.get(0);
        long codeId = ((Number) code.get("id")).longValue();
        String status = (String) code.get("status");
        Timestamp expiresAt = (Timestamp) code.get("expires_at");

        if ("DISABLED".equals(status)) {
            throw exception(ACCESS_CODE_DISABLED);
        }
        if (expiresAt != null && expiresAt.toInstant().isBefore(Instant.now())) {
            throw exception(ACCESS_CODE_EXPIRED);
        }

        long accountId = accountLoginService.resolveAccount(appid, openid, unionid);

        if ("CONSUMED".equals(status)) {
            // 幂等裁决：同一账号此前兑换过该码 → 返回既有结果；他人兑换 → ALREADY_CONSUMED
            List<Long> previousAccounts = jdbcTemplate.query(
                    "SELECT account_id FROM access_code_redemption WHERE code_id = ?",
                    (rs, i) -> rs.getLong("account_id"), codeId);
            if (!previousAccounts.isEmpty() && previousAccounts.get(0) == accountId) {
                Optional<Long> grant = findActiveGrantId(accountId);
                Timestamp consumedAt = (Timestamp) code.get("consumed_at");
                throw new RedeemIdempotentReplayException(new RedemptionResult(accountId,
                        grant.orElse(-1L), false, (String) code.get("code_mask"),
                        consumedAt == null ? null : consumedAt.toInstant()));
            }
            throw exception(ACCESS_CODE_ALREADY_CONSUMED);
        }

        // 消费授权码（持有行锁，status 仍为 ACTIVE）
        jdbcTemplate.update(
                "UPDATE design_access_code SET status = 'CONSUMED', consumed_at = now(), update_time = now() "
                        + "WHERE id = ? AND status = 'ACTIVE'", codeId);

        // 兑换事实（code_id 唯一；FOR UPDATE 串行化下冲突不可达，出现即系统异常回滚重试）
        jdbcTemplate.update(
                "INSERT INTO access_code_redemption (id, code_id, account_id, appid, openid) "
                        + "VALUES (?, ?, ?, ?, ?)",
                IdWorker.getId(), codeId, accountId, appid, openid);

        // 访问授权：已有 ACTIVE 则保持（幂等）；并发下部分唯一索引兜底，冲突方重查
        Optional<Long> activeGrant = findActiveGrantId(accountId);
        long grantId;
        boolean firstTime;
        if (activeGrant.isPresent()) {
            grantId = activeGrant.get();
            firstTime = false;
        } else {
            grantId = IdWorker.getId();
            jdbcTemplate.update(
                    "INSERT INTO design_access_grant (id, account_id, status) VALUES (?, ?, 'ACTIVE') "
                            + "ON CONFLICT (account_id) WHERE status = 'ACTIVE' DO NOTHING",
                    grantId, accountId);
            grantId = findActiveGrantId(accountId).orElseThrow();
            firstTime = true;
        }
        log.info("[redeem][account={} 获得授权 grant={} code={}]",
                accountId, grantId, code.get("code_mask"));
        Timestamp consumedAt = (Timestamp) code.get("consumed_at");
        return new RedemptionResult(accountId, grantId, firstTime,
                (String) code.get("code_mask"), consumedAt == null ? Instant.now() : consumedAt.toInstant());
    }

    private Optional<Long> findActiveGrantId(long accountId) {
        List<Long> rows = jdbcTemplate.query(
                "SELECT id FROM design_access_grant WHERE account_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                (rs, i) -> rs.getLong("id"), accountId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

}
