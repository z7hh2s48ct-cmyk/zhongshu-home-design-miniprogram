package cn.iocoder.yudao.module.design.controller.admin;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

/**
 * F-2 运营公告：向全部激活（ACTIVE）账号 fan-out 站内消息（user_message）。
 * 小程序消息中心/未读角标天然生效；消息不再单独插已读回执，用户打开即未读可清。
 * 封顶 20,000 账号：当前业务量级内的安全上限，超限拒绝并提示分批（防止误发全量风暴）。
 */
@Tag(name = "管理后台 - 运营公告")
@RestController
@RequestMapping("/design/v1")
public class AdminAnnouncementController {

    private static final int MAX_RECIPIENTS = 20_000;

    @Resource
    private DataSource dataSource;

    @Resource
    private AuditPort audit;

    private TransactionTemplate tx;

    public AdminAnnouncementController(PlatformTransactionManager transactionManager) {
        this.tx = new TransactionTemplate(transactionManager);
    }

    @PostMapping("/announcements")
    @Operation(summary = "发送运营公告：fan-out 到全部激活账号站内消息；标题/内容必填，全程审计")
    @PreAuthorize("@ss.hasPermission('design:announcement:send')")
    public CommonResult<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        long actor = SecurityFrameworkUtils.getLoginUserId();
        String title = text(body.get("title"), 128);
        String content = text(body.get("content"), 1024);
        if (title == null || content == null) {
            throw new ServiceException(400, "标题与内容必填");
        }
        long recipients = tx.execute(status -> {
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Long count = jdbc.queryForObject(
                    "SELECT count(*) FROM account WHERE status = 'ACTIVE' AND deleted = FALSE", Long.class);
            if (count == null || count <= 0) {
                throw new ServiceException(400, "当前没有可送达的激活账号");
            }
            if (count > MAX_RECIPIENTS) {
                throw new ServiceException(400, "激活账号超过 " + MAX_RECIPIENTS + "，请分批发送");
            }
            var ids = jdbc.queryForList(
                    "SELECT id FROM account WHERE status = 'ACTIVE' AND deleted = FALSE ORDER BY id", Long.class);
            Timestamp now = jdbc.queryForObject("SELECT now()", Timestamp.class);
            jdbc.batchUpdate(
                    "INSERT INTO user_message (id, user_id, message_type, title, content, tenant_id, creator, updater, create_time, update_time, deleted) "
                            + "VALUES (?, ?, 'ANNOUNCEMENT', ?, ?, 0, ?, ?, ?, ?, FALSE)",
                    ids.stream().map(id -> new Object[]{IdWorker.getId(), id, title, content,
                            String.valueOf(actor), String.valueOf(actor), now, now}).toList());
            audit.record(AuditEventMessage.builder()
                    .eventType("ANNOUNCEMENT").actorType(AuditEventMessage.ActorType.ADMIN)
                    .actorId(String.valueOf(actor)).action("SEND")
                    .bizType("user_message").bizId("announcement")
                    .result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L)
                    .detail(Map.of("title", title, "recipients", count)).build());
            return count;
        });
        return success(Map.of("recipients", recipients));
    }

    private String text(Object value, int max) {
        if (value == null) return null;
        String trimmed = value.toString().trim();
        return trimmed.isEmpty() || trimmed.length() > max ? null : trimmed;
    }

}
