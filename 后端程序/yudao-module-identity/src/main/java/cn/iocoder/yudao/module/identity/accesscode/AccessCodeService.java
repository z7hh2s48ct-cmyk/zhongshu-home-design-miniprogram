package cn.iocoder.yudao.module.identity.accesscode;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.DeliveryPort;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.IssuedTicket;
import cn.iocoder.yudao.module.infra.zhongshu.delivery.TicketConsumption;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_EXPIRED;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_INVALID;
import static cn.iocoder.yudao.module.identity.enums.ErrorCodeConstants.ACCESS_CODE_SECRET_ALREADY_EXPOSED;

/**
 * 授权码批次：生成、受控单码复制、掩码查询、停用与删除（架构 §6.1）
 *
 * 合同：
 * - 批次明文只以 AES-GCM 密文留存；INLINE 在创建响应内返回；
 * - 授权管理员可再次复制仍未使用、未过期的单码，完整批次票据仍只能消费一次；
 * - 列表、搜索、日志、普通导出永远只返回掩码。
 */
@Slf4j
@Service
public class AccessCodeService {

    public record BatchCreateResult(long batchId, String deliveryMode, List<String> oneTimeCodes) {
    }

    public record AccessCodeRow(long id, String codeMask, long batchId, String status, boolean canCopy,
                                Instant issuedAt, Instant consumedAt, Instant secretExposedAt,
                                Instant expiresAt, Long boundAccountId) {
    }

    /** TICKET 交付票据有效期（秒） */
    private static final long DELIVERY_TICKET_TTL_SECONDS = 600;

    private final JdbcTemplate jdbcTemplate;

    private final TransactionTemplate txTemplate;

    private final AccessCodeCipher cipher;

    private final DeliveryPort deliveryPort;

    public AccessCodeService(DataSource dataSource, PlatformTransactionManager transactionManager,
                             AccessCodeCipher cipher, DeliveryPort deliveryPort) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.cipher = cipher;
        this.deliveryPort = deliveryPort;
    }

    /** 创建批次；INLINE 返回一次性明文列表，TICKET 返回空列表（明文进加密制品） */
    public BatchCreateResult createBatch(int quantity, String deliveryMode, Integer validityDays,
                                         String purposeNote, String issuedBy) {
        List<String> codes = new ArrayList<>(quantity);
        for (int i = 0; i < quantity; i++) {
            codes.add(cipher.generate());
        }
        long batchId = IdWorker.getId();
        Timestamp expiresAt = validityDays == null ? null
                : Timestamp.from(Instant.now().plusSeconds(validityDays * 86400L));
        boolean inline = "INLINE".equals(deliveryMode);

        txTemplate.execute(status -> {
            jdbcTemplate.update(
                    "INSERT INTO design_access_code_batch (id, quantity, delivery_mode, exposed_count, "
                            + "issued_by, purpose_note, expires_at, encrypted_artifact, artifact_nonce) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    batchId, quantity, deliveryMode, inline ? quantity : 0,
                    issuedBy, purposeNote, expiresAt,
                    cipher.encryptArtifact(codes),
                    null);
            for (String code : codes) {
                jdbcTemplate.update(
                        "INSERT INTO design_access_code (id, batch_id, code_hash, pepper_version, code_mask, "
                                + "secret_exposed_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                        IdWorker.getId(), batchId, cipher.hash(code), cipher.pepperVersion(),
                        cipher.mask(code), inline ? Timestamp.from(Instant.now()) : null, expiresAt);
            }
            return null;
        });
        log.info("[createBatch][batch={} mode={} quantity={} by={}]", batchId, deliveryMode, quantity, issuedBy);
        return new BatchCreateResult(batchId, deliveryMode, inline ? codes : List.of());
    }

    /** 签发 TICKET 一次性交付票据；明文已暴露（INLINE 批次或已交付过）即拒绝 */
    public IssuedTicket issueDeliveryTicket(long batchId, String operator) {
        requireTicketDeliverable(batchId);
        Long operatorUserId = parseLongOrNull(operator);
        return deliveryPort.issueDeliveryTicket("ACCESS_CODE_BATCH",
                String.valueOf(batchId), operatorUserId, DELIVERY_TICKET_TTL_SECONDS);
    }

    private Long parseLongOrNull(String value) {
        try {
            return value == null ? null : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 凭一次性票据交付完整明文：先原子消费票据，再解密输出。
     * 同一票据第二次消费（重放）与票据过期/未知分别映射稳定错误码。
     */
    public List<String> exportByTicket(long batchId, String ticketToken, String consumerId) {
        if (ticketToken == null || ticketToken.isBlank()) {
            throw exception(ACCESS_CODE_INVALID);
        }
        requireTicketDeliverable(batchId);
        TicketConsumption consumption = deliveryPort.consumeDeliveryTicket(ticketToken, consumerId);
        if (consumption.getOutcome() == TicketConsumption.Outcome.CONSUMED_NOW) {
            // 一票一批次：票据必须与本批次绑定（跨批次使用视为无效，票据已作废可接受）
            if (!String.valueOf(batchId).equals(consumption.getBizRef())
                    || !"ACCESS_CODE_BATCH".equals(consumption.getPurpose())) {
                throw exception(ACCESS_CODE_INVALID);
            }
            return txTemplate.execute(status -> exportAndDestroyArtifact(batchId, consumerId));
        }
        if (consumption.getOutcome() == TicketConsumption.Outcome.ALREADY_CONSUMED) {
            throw exception(ACCESS_CODE_SECRET_ALREADY_EXPOSED);
        }
        if (consumption.getOutcome() == TicketConsumption.Outcome.EXPIRED) {
            throw exception(ACCESS_CODE_EXPIRED);
        }
        throw exception(ACCESS_CODE_INVALID);
    }

    private List<String> exportAndDestroyArtifact(long batchId, String consumerId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT encrypted_artifact, quantity, exposed_count FROM design_access_code_batch "
                        + "WHERE id = ? AND delivery_mode = 'TICKET' FOR UPDATE", batchId);
        if (rows.isEmpty()) {
            throw exception(ACCESS_CODE_INVALID);
        }
        if (((Number) rows.get(0).get("exposed_count")).intValue() > 0) {
            throw exception(ACCESS_CODE_SECRET_ALREADY_EXPOSED);
        }
        byte[] artifact = rows.get(0).get("encrypted_artifact") == null
                ? null : (byte[]) rows.get(0).get("encrypted_artifact");
        if (artifact == null) {
            throw exception(ACCESS_CODE_SECRET_ALREADY_EXPOSED);
        }
        List<String> codes = cipher.decryptArtifact(artifact);
        jdbcTemplate.update(
                "UPDATE design_access_code_batch SET exposed_count = quantity, update_time = now() WHERE id = ?",
                batchId);
        jdbcTemplate.update(
                "UPDATE design_access_code SET secret_exposed_at = now(), update_time = now() "
                        + "WHERE batch_id = ? AND secret_exposed_at IS NULL", batchId);
        log.warn("[exportByTicket][batch={} 完整明文已一次性交付给 {}]", batchId, consumerId);
        return codes;
    }

    /** 受控复制仍可兑换的单码；明文只从加密批次制品中临时解密，不写日志或普通列。 */
    public String copyActiveCode(long codeId, String operator) {
        return txTemplate.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT c.batch_id, c.status, c.deleted, c.expires_at, b.encrypted_artifact "
                            + "FROM design_access_code c JOIN design_access_code_batch b ON b.id = c.batch_id "
                            + "WHERE c.id = ? AND b.deleted = FALSE FOR UPDATE OF c, b", codeId);
            if (rows.isEmpty()) {
                throw exception(ACCESS_CODE_INVALID);
            }
            Map<String, Object> row = rows.get(0);
            if (Boolean.TRUE.equals(row.get("deleted")) || !"ACTIVE".equals(row.get("status"))) {
                throw exception(ACCESS_CODE_INVALID);
            }
            Timestamp expiresAt = (Timestamp) row.get("expires_at");
            if (expiresAt != null && !expiresAt.toInstant().isAfter(Instant.now())) {
                throw exception(ACCESS_CODE_EXPIRED);
            }
            byte[] artifact = (byte[]) row.get("encrypted_artifact");
            if (artifact == null) {
                throw exception(ACCESS_CODE_INVALID);
            }
            long batchId = ((Number) row.get("batch_id")).longValue();
            // Ciphertext keeps creation order; Snowflake IDs are allocated during those same inserts.
            List<Long> ids = jdbcTemplate.queryForList(
                    "SELECT id FROM design_access_code WHERE batch_id = ? ORDER BY id", Long.class, batchId);
            int index = ids.indexOf(codeId);
            List<String> codes = cipher.decryptArtifact(artifact);
            if (index < 0 || index >= codes.size()) {
                throw exception(ACCESS_CODE_INVALID);
            }
            jdbcTemplate.update(
                    "UPDATE design_access_code SET secret_exposed_at = COALESCE(secret_exposed_at, now()), "
                            + "update_time = now() WHERE id = ?", codeId);
            jdbcTemplate.update(
                    "UPDATE design_access_code_batch SET exposed_count = CASE WHEN exposed_count = 0 "
                            + "THEN 1 ELSE exposed_count END, update_time = now() "
                            + "WHERE id = ?", batchId);
            log.warn("[copyActiveCode][code={} batch={} 明文复制操作员={}]", codeId, batchId, operator);
            return codes.get(index);
        });
    }

    /** 逻辑删除未兑换码并同步停用；保留兑换与审计事实。 */
    public boolean deleteUnusedCode(long codeId, String operator) {
        boolean deleted = jdbcTemplate.update(
                "UPDATE design_access_code c SET status = 'DISABLED', deleted = TRUE, update_time = now() "
                        + "WHERE c.id = ? AND c.deleted = FALSE AND c.consumed_at IS NULL "
                        + "AND c.status IN ('ACTIVE', 'DISABLED') "
                        + "AND NOT EXISTS (SELECT 1 FROM access_code_redemption r "
                        + "WHERE r.code_id = c.id AND r.deleted = FALSE)", codeId) == 1;
        if (deleted) {
            log.warn("[deleteUnusedCode][code={} 操作员={} 已逻辑删除并停用]", codeId, operator);
        }
        return deleted;
    }

    public boolean disableCode(long codeId) {
        return jdbcTemplate.update(
                "UPDATE design_access_code SET status = 'DISABLED', update_time = now() "
                        + "WHERE id = ? AND status = 'ACTIVE'", codeId) == 1;
    }

    /** 掩码分页查询：任何路径都不返回明文或完整 hash */
    public List<AccessCodeRow> pageCodes(Long batchId, String status, int pageNo, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE c.deleted = FALSE");
        List<Object> args = new ArrayList<>();
        if (batchId != null) {
            where.append(" AND c.batch_id = ?");
            args.add(batchId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND c.status = ?");
            args.add(status);
        }
        args.add(pageSize);
        args.add((long) Math.max(pageNo - 1, 0) * pageSize);
        return jdbcTemplate.query(
                "SELECT c.id, c.batch_id, c.code_mask, c.status, "
                        + "(c.status = 'ACTIVE' AND (c.expires_at IS NULL OR c.expires_at > now()) "
                        + "AND b.encrypted_artifact IS NOT NULL) AS can_copy, c.issued_at, c.consumed_at, "
                        + "c.secret_exposed_at, c.expires_at, r.account_id AS bound_account_id "
                        + "FROM design_access_code c "
                        + "JOIN design_access_code_batch b ON b.id = c.batch_id AND b.deleted = FALSE "
                        + "LEFT JOIN access_code_redemption r ON r.code_id = c.id AND r.deleted = FALSE"
                        + where + " ORDER BY c.id LIMIT ? OFFSET ?",
                (rs, i) -> new AccessCodeRow(rs.getLong("id"), rs.getString("code_mask"),
                        rs.getLong("batch_id"), rs.getString("status"), rs.getBoolean("can_copy"),
                        rs.getTimestamp("issued_at").toInstant(),
                        rs.getTimestamp("consumed_at") == null ? null : rs.getTimestamp("consumed_at").toInstant(),
                        rs.getTimestamp("secret_exposed_at") == null ? null
                                : rs.getTimestamp("secret_exposed_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getObject("bound_account_id") == null ? null : rs.getLong("bound_account_id")),
                args.toArray());
    }

    public long countCodes(Long batchId, String status) {
        StringBuilder where = new StringBuilder(" WHERE deleted = FALSE");
        List<Object> args = new ArrayList<>();
        if (batchId != null) {
            where.append(" AND batch_id = ?");
            args.add(batchId);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            args.add(status);
        }
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM design_access_code" + where,
                Long.class, args.toArray());
        return n == null ? 0 : n;
    }

    /**
     * 授权码四态计数（页面 07 统计卡）。
     * EXPIRED 是派生态：码本身仍 ACTIVE 但已过有效期（与兑换入口的过期判定同一口径），
     * 计入 EXPIRED 的同时从 ACTIVE 中扣除，四类互斥且合计等于全量。
     */
    public Map<String, Long> statusCounts() {
        Map<String, Long> counts = jdbcTemplate.query(
                "SELECT status, count(*) AS cnt FROM design_access_code WHERE deleted = FALSE GROUP BY status",
                rs -> {
                    Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        byStatus.put(rs.getString("status"), rs.getLong("cnt"));
                    }
                    return byStatus;
                });
        Long expired = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM design_access_code "
                        + "WHERE deleted = FALSE AND status = 'ACTIVE' "
                        + "AND expires_at IS NOT NULL AND expires_at <= now()",
                Long.class);
        long expiredCount = expired == null ? 0 : expired;
        long activeCount = counts.getOrDefault("ACTIVE", 0L) - expiredCount;
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        result.put("UNUSED", Math.max(activeCount, 0));
        result.put("BOUND", counts.getOrDefault("CONSUMED", 0L));
        result.put("EXPIRED", expiredCount);
        result.put("DISABLED", counts.getOrDefault("DISABLED", 0L));
        return result;
    }

    private void requireTicketDeliverable(long batchId) {
        Integer exposed = jdbcTemplate.queryForObject(
                "SELECT exposed_count FROM design_access_code_batch WHERE id = ? AND deleted = FALSE",
                Integer.class, batchId);
        if (exposed == null) {
            throw exception(ACCESS_CODE_INVALID);
        }
        if (exposed > 0) {
            throw exception(ACCESS_CODE_SECRET_ALREADY_EXPOSED);
        }
    }

}
