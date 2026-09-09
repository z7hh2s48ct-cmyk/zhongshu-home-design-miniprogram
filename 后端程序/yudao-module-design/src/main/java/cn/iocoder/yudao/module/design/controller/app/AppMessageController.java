package cn.iocoder.yudao.module.design.controller.app;

import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.CursorPageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppMessageRespVO;
import cn.iocoder.yudao.module.design.notification.MessageService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;

@Tag(name = "小程序 - 消息中心（顶部铃铛为唯一入口）")
@RestController
@RequestMapping("/design/v1/messages")
@PermitAll
public class AppMessageController {

    @Resource
    private MessageService messageService;

    @Resource
    private IdentitySessionPort identitySessionPort;

    @GetMapping
    @Operation(summary = "消息列表（游标分页；Outbox 驱动，重复投递幂等）")
    public CommonResult<CursorPageResult<AppMessageRespVO>> getMessagePage(
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") Integer limit,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = requireAccountId(authorization);
        var page = messageService.listPage(userId, cursor, limit == null ? 20 : limit);
        List<AppMessageRespVO> list = page.list().stream()
                .map(this::toVo).toList();
        return success(new CursorPageResult<>(list, page.nextCursor()));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "未读消息数（唯一一套未读数合同）")
    public CommonResult<Long> getUnreadCount(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(messageService.unreadCount(requireAccountId(authorization)));
    }

    @PostMapping("/{messageId}/read-receipts")
    @Operation(summary = "标记已读回执（幂等）")
    public CommonResult<Boolean> markRead(
            @PathVariable("messageId") String messageId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return success(messageService.markRead(requireAccountId(authorization),
                Long.parseLong(messageId)));
    }

    private long requireAccountId(String authorization) {
        String token = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7) : authorization;
        // 审查 H4：业务端点统一要求非受限会话（受限会话仅可查准入/协议/兑换授权码）
        return identitySessionPort.requireUnrestricted(token).accountId();
    }

    private AppMessageRespVO toVo(Map<String, Object> row) {
        AppMessageRespVO vo = new AppMessageRespVO();
        vo.setMessageId(String.valueOf(row.get("id")));
        vo.setMessageType((String) row.get("message_type"));
        vo.setTitle((String) row.get("title"));
        vo.setContent((String) row.get("content"));
        vo.setBizType((String) row.get("biz_type"));
        vo.setBizId((String) row.get("biz_id"));
        vo.setRead(Boolean.TRUE.equals(row.get("read")));
        Object createTime = row.get("create_time");
        vo.setSentAt(createTime instanceof Timestamp ts ? ts.toLocalDateTime() : null);
        return vo;
    }

}
