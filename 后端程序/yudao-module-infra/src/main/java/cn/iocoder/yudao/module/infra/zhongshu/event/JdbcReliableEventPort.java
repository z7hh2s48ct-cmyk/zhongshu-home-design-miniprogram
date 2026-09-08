package cn.iocoder.yudao.module.infra.zhongshu.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.util.Map;

/**
 * ReliableEventPort JDBC 实现
 *
 * 使用业务主数据源：与业务写同一线程绑定事务，业务回滚则事件一并回滚。
 */
@Repository
public class JdbcReliableEventPort implements ReliableEventPort {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public JdbcReliableEventPort(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    @Override
    public long append(OutboxEventMessage message) {
        String payload = toJson(message.getPayload());
        String headers = message.getHeaders() == null ? null : toJson(message.getHeaders());
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO outbox_event (event_type, biz_type, biz_id, payload, headers, tenant_id) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?) RETURNING id",
                Long.class,
                message.getEventType(), message.getBizType(), message.getBizId(),
                payload, headers, message.getTenantId() == null ? 0L : message.getTenantId());
        return id == null ? -1L : id;
    }

    private String toJson(Map<String, ?> value) {
        try {
            return JSON.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox payload 序列化失败", e);
        }
    }

}
