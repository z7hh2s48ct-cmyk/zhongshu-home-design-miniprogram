package cn.iocoder.yudao.module.identity.privacy;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 隐私协议同意与数据主体请求（架构 §6.10，页面 15 设置区）。
 *
 * 归属说明：privacy_consent / data_subject_request 两张表建在 design 迁移段
 * （V20260905.206，P7B 打包交付），但业务语义属于账号主体域，因此服务层放在
 * identity 模块。模块间无 Maven 依赖，仅共享同一个 PostgreSQL 库；
 * 若后续要严格归属，需新迁移搬表，不在本服务内处理。
 *
 * 本模块管理同意事实和申请；server PrivacyLifecycleService 负责跨域导出、
 * 受权处理关闭及会话吊销，下载票据独立签发且仅存哈希。
 */
@Slf4j
@Service
public class PrivacyService {

    /** 当前生效的协议版本。协议文本更新时递增，旧同意记录不失效（按版本各自留存） */
    public static final String CURRENT_PRIVACY_VERSION = "v1";

    /** 三类协议一次全部征得同意：用户协议、隐私政策、第三方 AI 处理告知 */
    private static final List<String> POLICY_TYPES =
            List.of("USER_AGREEMENT", "PRIVACY_POLICY", "AI_PROCESSING_NOTICE");

    public record ConsentRow(long id, String policyType, String version, Instant acceptedAt) {
    }

    public record SubjectRequest(long requestId, String requestType, String status,
                                 Instant createdAt, Instant completedAt, String downloadTicket) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public PrivacyService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.tx = new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
    }

    // ========== 协议同意 ==========

    public List<ConsentRow> listConsents(long userId) {
        return jdbcTemplate.query(
                "SELECT id, policy_type, version, accepted_at FROM privacy_consent "
                        + "WHERE user_id = ? AND deleted = FALSE ORDER BY accepted_at DESC, id DESC",
                (rs, i) -> new ConsentRow(rs.getLong("id"), rs.getString("policy_type"),
                        rs.getString("version"),
                        rs.getTimestamp("accepted_at") == null ? null
                                : rs.getTimestamp("accepted_at").toInstant()),
                userId);
    }

    /** 记录当前版本三类协议的同意事实；同版本已同意的类型跳过（同意事实按 (user, type, version) 去重） */
    public void acceptCurrentConsents(long userId) {
        for (String policyType : POLICY_TYPES) {
            Integer exists = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM privacy_consent WHERE user_id = ? AND policy_type = ? "
                            + "AND version = ? AND deleted = FALSE",
                    Integer.class, userId, policyType, CURRENT_PRIVACY_VERSION);
            if (exists != null && exists > 0) {
                continue;
            }
            jdbcTemplate.update(
                    "INSERT INTO privacy_consent (id, user_id, policy_type, version) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (user_id,policy_type,version) WHERE deleted=FALSE DO NOTHING",
                    IdWorker.getId(), userId, policyType, CURRENT_PRIVACY_VERSION);
        }
        log.info("[acceptCurrentConsents][user={} version={}]", userId, CURRENT_PRIVACY_VERSION);
    }

    /** 当前版本是否已完整同意（三类齐全）；新用户首次进入设置区前为 false */
    public boolean hasAcceptedCurrentVersion(long userId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(DISTINCT policy_type) FROM privacy_consent WHERE user_id = ? "
                        + "AND version = ? AND deleted = FALSE",
                Integer.class, userId, CURRENT_PRIVACY_VERSION);
        return n != null && n >= POLICY_TYPES.size();
    }

    // ========== 数据主体请求 ==========

    /** 登记 EXPORT / CLOSE_ACCOUNT 请求；同类型存在未完结请求时幂等返回既有请求 */
    public SubjectRequest createSubjectRequest(long userId, String requestType) {
        if (requestType == null || !List.of("EXPORT", "CLOSE_ACCOUNT").contains(requestType)) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(400, "请求类型无效");
        return tx.execute(status -> {
        // Serialize requests with closure and with concurrent requests from another instance.
        var account = jdbcTemplate.queryForList("SELECT status FROM account WHERE id=? AND deleted=FALSE FOR UPDATE", String.class,userId);
        if (account.isEmpty() || !"ACTIVE".equals(account.get(0))) throw new cn.iocoder.yudao.framework.common.exception.ServiceException(403, "账号已停用或关闭");
        List<SubjectRequest> open = jdbcTemplate.query(
                "SELECT id, request_type, status, create_time, completed_at, download_ticket "
                        + "FROM data_subject_request WHERE user_id = ? AND request_type = ? "
                        + "AND status IN ('PENDING','PROCESSING') AND deleted = FALSE "
                        + "ORDER BY id DESC LIMIT 1",
                (rs, i) -> mapRequest(rs), userId, requestType);
        if (!open.isEmpty()) {
            return open.get(0);
        }
        long requestId = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO data_subject_request (id, user_id, request_type, status) VALUES (?, ?, ?, 'PENDING')",
                requestId, userId, requestType);
        log.info("[createSubjectRequest][user={} type={} id={}]", userId, requestType, requestId);
        return new SubjectRequest(requestId, requestType, "PENDING", Instant.now(), null, null);
        });
    }

    public List<java.util.Map<String,Object>> listRequests(long userId) {
        var rows = jdbcTemplate.queryForList("SELECT id::text AS \"requestId\",request_type AS \"requestType\",status, "
                + "create_time AS \"createdAt\",completed_at AS \"completedAt\",error_code AS \"errorCode\", "
                + "(file_expires_at>now()) AS \"downloadable\" FROM data_subject_request WHERE user_id=? AND deleted=FALSE ORDER BY id DESC LIMIT 50",userId);
        return rows;
    }

    public Optional<SubjectRequest> getSubjectRequest(long userId, long requestId) {
        List<SubjectRequest> rows = jdbcTemplate.query(
                "SELECT id, request_type, status, create_time, completed_at, download_ticket "
                        + "FROM data_subject_request WHERE id = ? AND user_id = ? AND deleted = FALSE",
                (rs, i) -> mapRequest(rs), requestId, userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private SubjectRequest mapRequest(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new SubjectRequest(rs.getLong("id"), rs.getString("request_type"),
                rs.getString("status"),
                rs.getTimestamp("create_time") == null ? null
                        : rs.getTimestamp("create_time").toInstant(),
                completed == null ? null : completed.toInstant(),
                rs.getString("download_ticket"));
    }

}
