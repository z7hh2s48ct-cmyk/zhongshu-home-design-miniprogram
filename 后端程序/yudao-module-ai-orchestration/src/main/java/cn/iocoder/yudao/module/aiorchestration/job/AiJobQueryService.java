package cn.iocoder.yudao.module.aiorchestration.job;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.List;
import java.util.Optional;

/**
 * AI 任务只读查询（App/Admin 共用）
 */
@Service
public class AiJobQueryService {

    private final JdbcTemplate jdbcTemplate;

    public AiJobQueryService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    public Optional<AiJobOrchestrationService.JobSnapshot> getJob(long jobId) {
        List<AiJobOrchestrationService.JobSnapshot> rows = jdbcTemplate.query(baseSelect()
                + " WHERE id = ? AND deleted = FALSE", (rs, i) -> mapRow(rs), jobId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public long countJobs(String status, String phase) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ai_job" + where(status, phase), Long.class);
        return n == null ? 0 : n;
    }

    public List<AiJobOrchestrationService.JobSnapshot> pageJobs(String status, String phase,
                                                                int pageNo, int pageSize) {
        return jdbcTemplate.query(baseSelect() + where(status, phase)
                        + " ORDER BY id LIMIT ? OFFSET ?",
                (rs, i) -> mapRow(rs), pageSize, (long) Math.max(pageNo - 1, 0) * pageSize);
    }

    private String baseSelect() {
        return "SELECT id, user_id, phase, status, requested_count, accepted_count, progress, "
                + "cancel_seq FROM ai_job";
    }

    private String where(String status, String phase) {
        StringBuilder sb = new StringBuilder(" WHERE deleted = FALSE");
        if (status != null && !status.isBlank()) {
            sb.append(" AND status = '").append(status.replace("'", "''")).append("'");
        }
        if (phase != null && !phase.isBlank()) {
            sb.append(" AND phase = '").append(phase.replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private AiJobOrchestrationService.JobSnapshot mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AiJobOrchestrationService.JobSnapshot(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("phase"), rs.getString("status"),
                rs.getInt("requested_count"), rs.getInt("accepted_count"), rs.getInt("progress"),
                rs.getObject("cancel_seq") == null ? "ACTIVE" : "CANCEL_REQUESTED");
    }

}
