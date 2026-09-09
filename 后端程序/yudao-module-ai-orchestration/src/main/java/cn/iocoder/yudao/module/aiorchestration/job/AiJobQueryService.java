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
        int size = Math.max(1, Math.min(pageSize, 100));
        return jdbcTemplate.query(baseSelect() + where(status, phase)
                        + " ORDER BY create_time DESC, id DESC LIMIT ? OFFSET ?",
                (rs, i) -> mapRow(rs), size, (long) Math.max(pageNo - 1, 0) * size);
    }

    static String baseSelect() {
        return "SELECT id, user_id, phase, status, requested_count, accepted_count, progress, "
                + "cancel_seq, project_ref, unit_point_cost, total_point_cost, create_time, "
                + "(SELECT SUM(s.refunded_points) FROM ai_job_settlement s WHERE s.job_id = ai_job.id "
                + "AND s.deleted = FALSE) AS refunded_points, "
                + "(SELECT MAX(s.create_time) FROM ai_job_settlement s WHERE s.job_id = ai_job.id "
                + "AND s.deleted = FALSE) AS finished_at FROM ai_job";
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

    static AiJobOrchestrationService.JobSnapshot mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        boolean terminal = List.of("SUCCEEDED", "PARTIALLY_SUCCEEDED", "FAILED", "CANCELLED").contains(rs.getString("status"));
        return new AiJobOrchestrationService.JobSnapshot(
                rs.getLong("id"), rs.getLong("user_id"), rs.getString("phase"), rs.getString("status"),
                rs.getInt("requested_count"), rs.getInt("accepted_count"), terminal ? 100 : rs.getInt("progress"),
                rs.getObject("cancel_seq") == null ? "ACTIVE" : "CANCEL_REQUESTED", rs.getString("project_ref"),
                rs.getObject("unit_point_cost", Long.class), rs.getObject("total_point_cost", Long.class),
                rs.getObject("refunded_points") == null ? null : rs.getBigDecimal("refunded_points").longValueExact(),
                rs.getTimestamp("create_time").toInstant(),
                terminal && rs.getTimestamp("finished_at") != null ? rs.getTimestamp("finished_at").toInstant() : null);
    }

}
