package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Repository
public class JdbcDeliveryPort implements DeliveryPort {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbcTemplate;

    public JdbcDeliveryPort(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    // ========== 导出任务 ==========

    @Override
    public long createExportJob(ExportJobRequest request) {
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO export_job (job_type, requester_type, requester_user_id, filter_snapshot, field_scope) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb)) RETURNING id",
                Long.class,
                request.getJobType(),
                request.getRequesterType() == null ? "ADMIN" : request.getRequesterType(),
                request.getRequesterUserId(),
                toJson(request.getFilterSnapshot()), toJson(request.getFieldScope()));
        return id == null ? -1L : id;
    }

    @Override
    public ExportJobSnapshot getExportJob(long jobId) {
        List<ExportJobSnapshot> list = jdbcTemplate.query(
                "SELECT id, job_type, status, requester_type, requester_user_id, filter_snapshot::text, "
                        + "file_asset_id, file_sha256, error, expires_at, create_time "
                        + "FROM export_job WHERE id = ?",
                (rs, i) -> ExportJobSnapshot.builder()
                        .jobId(rs.getLong("id"))
                        .jobType(rs.getString("job_type"))
                        .status(ExportJobSnapshot.Status.valueOf(rs.getString("status")))
                        .requesterType(rs.getString("requester_type"))
                        .requesterUserId(rs.getLong("requester_user_id"))
                        .filterSnapshot(fromJson(rs.getString("filter_snapshot")))
                        .fileAssetId(rs.getString("file_asset_id"))
                        .fileSha256(rs.getString("file_sha256"))
                        .error(rs.getString("error"))
                        .expiresAt(toInstant(rs.getTimestamp("expires_at")))
                        .createTime(toInstant(rs.getTimestamp("create_time")))
                        .build(),
                jobId);
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public boolean completeExportJob(long jobId, String fileAssetId, String fileSha256, long ttlSeconds) {
        return jdbcTemplate.update(
                "UPDATE export_job SET status = 'COMPLETED', file_asset_id = ?, file_sha256 = ?, "
                        + "expires_at = now() + (? * interval '1 second'), update_time = now() "
                        + "WHERE id = ? AND status IN ('PENDING','RUNNING')",
                fileAssetId, fileSha256, ttlSeconds, jobId) == 1;
    }

    @Override
    public boolean failExportJob(long jobId, String error) {
        return jdbcTemplate.update(
                "UPDATE export_job SET status = 'FAILED', error = ?, update_time = now() "
                        + "WHERE id = ? AND status IN ('PENDING','RUNNING')",
                error, jobId) == 1;
    }

    // ========== 一次性票据 ==========

    @Override
    public IssuedTicket issueDeliveryTicket(String purpose, String bizRef, Long ownerUserId, long ttlSeconds) {
        return issueTicket("one_time_delivery_ticket", purpose, bizRef, ownerUserId, ttlSeconds);
    }

    @Override
    public IssuedTicket issueDownloadTicket(String purpose, String bizRef, Long ownerUserId, long ttlSeconds) {
        return issueTicket("one_time_download_ticket", purpose, bizRef, ownerUserId, ttlSeconds);
    }

    private IssuedTicket issueTicket(String table, String purpose, String bizRef, Long ownerUserId, long ttlSeconds) {
        byte[] raw = new byte[32];
        SECURE_RANDOM.nextBytes(raw);
        String token = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String hash = sha256Hex(token);
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO " + table + " (ticket_hash, purpose, biz_ref, owner_user_id, expires_at) "
                        + "VALUES (?, ?, ?, ?, now() + (? * interval '1 second')) RETURNING id, expires_at",
                (rs, i) -> rs.getLong("id"),
                hash, purpose, bizRef, ownerUserId, ttlSeconds);
        Instant expiresAt = jdbcTemplate.queryForObject(
                "SELECT expires_at FROM " + table + " WHERE id = ?",
                (rs, num) -> rs.getTimestamp("expires_at").toInstant(), id);
        return IssuedTicket.builder().token(token).ticketId(id).expiresAt(expiresAt).build();
    }

    @Override
    public TicketConsumption consumeDeliveryTicket(String token, String consumerId) {
        return consumeTicket("one_time_delivery_ticket", token, consumerId);
    }

    @Override
    public TicketConsumption consumeDownloadTicket(String token, String consumerId) {
        return consumeTicket("one_time_download_ticket", token, consumerId);
    }

    @Override
    public TicketConsumption consumeOwnedDownloadTicket(String token, long ownerId, String purpose, String bizRef) {
        if (token == null || token.isBlank()) return TicketConsumption.builder().outcome(TicketConsumption.Outcome.UNKNOWN).build();
        int consumed = jdbcTemplate.update("UPDATE one_time_download_ticket SET status='CONSUMED',consumed_at=now(),consumer_id=? "
                + "WHERE ticket_hash=? AND owner_user_id=? AND purpose=? AND biz_ref=? AND status='ACTIVE' AND expires_at>now()",
                String.valueOf(ownerId),sha256Hex(token),ownerId,purpose,bizRef);
        return TicketConsumption.builder().outcome(consumed==1?TicketConsumption.Outcome.CONSUMED_NOW:TicketConsumption.Outcome.UNKNOWN)
                .purpose(consumed==1?purpose:null).bizRef(consumed==1?bizRef:null).build();
    }

    private TicketConsumption consumeTicket(String table, String token, String consumerId) {
        String hash = sha256Hex(token);
        // 先原子标记 CONSUMED：并发与重放都只有一条 UPDATE 命中
        List<Map<String, Object>> consumed = jdbcTemplate.queryForList(
                "UPDATE " + table + " SET status = 'CONSUMED', consumed_at = now(), consumer_id = ? "
                        + "WHERE ticket_hash = ? AND status = 'ACTIVE' AND expires_at > now() "
                        + "RETURNING purpose, biz_ref",
                consumerId, hash);
        if (!consumed.isEmpty()) {
            return TicketConsumption.builder()
                    .outcome(TicketConsumption.Outcome.CONSUMED_NOW)
                    .purpose((String) consumed.get(0).get("purpose"))
                    .bizRef((String) consumed.get(0).get("biz_ref"))
                    .build();
        }
        // 未命中：区分 UNKNOWN / ALREADY_CONSUMED / EXPIRED / REVOKED
        List<Map<String, Object>> existing = jdbcTemplate.queryForList(
                "SELECT status, purpose, biz_ref FROM " + table + " WHERE ticket_hash = ?", hash);
        if (existing.isEmpty()) {
            return TicketConsumption.builder().outcome(TicketConsumption.Outcome.UNKNOWN).build();
        }
        Map<String, Object> row = existing.get(0);
        String status = (String) row.get("status");
        TicketConsumption.Outcome outcome = switch (status) {
            case "CONSUMED" -> TicketConsumption.Outcome.ALREADY_CONSUMED;
            case "REVOKED" -> TicketConsumption.Outcome.REVOKED;
            default -> TicketConsumption.Outcome.EXPIRED; // ACTIVE 但已过期，或已标记 EXPIRED
        };
        return TicketConsumption.builder().outcome(outcome)
                .purpose((String) row.get("purpose")).bizRef((String) row.get("biz_ref")).build();
    }

    // ========== 工具 ==========

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(value == null ? Map.of() : value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("导出任务快照序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("导出任务快照反序列化失败", e);
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

}
