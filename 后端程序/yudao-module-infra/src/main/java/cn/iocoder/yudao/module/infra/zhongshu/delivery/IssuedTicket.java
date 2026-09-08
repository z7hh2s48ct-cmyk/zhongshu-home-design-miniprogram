package cn.iocoder.yudao.module.infra.zhongshu.delivery;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * 一次性票据签发结果：明文 token 仅此一次返回，数据库只存 SHA-256
 */
@Value
@Builder
public class IssuedTicket {

    /** 一次性明文 token（Base64URL，32 字节随机） */
    String token;

    Long ticketId;

    Instant expiresAt;

}
