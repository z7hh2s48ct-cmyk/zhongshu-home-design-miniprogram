package cn.iocoder.yudao.module.infra.zhongshu.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;

@Repository
public class JdbcAuditPort implements AuditPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public JdbcAuditPort(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public long record(AuditEventMessage message) {
        String detail = null;
        if (message.getDetail() != null) {
            try {
                detail = JSON.writeValueAsString(message.getDetail());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("审计明细序列化失败", e);
            }
        }
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO audit_event (event_type, actor_type, actor_id, action, biz_type, biz_id, result, detail, tenant_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?) RETURNING id",
                Long.class,
                message.getEventType(), message.getActorType().name(), message.getActorId(),
                message.getAction(), message.getBizType(), message.getBizId(),
                message.getResult().name(), detail, message.getTenantId() == null ? 0L : message.getTenantId());
        return id == null ? -1L : id;
    }

}
