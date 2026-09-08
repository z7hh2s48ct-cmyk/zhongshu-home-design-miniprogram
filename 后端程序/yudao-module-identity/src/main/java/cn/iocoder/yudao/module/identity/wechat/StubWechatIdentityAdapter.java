package cn.iocoder.yudao.module.identity.wechat;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 微信身份 Stub：同一 login code 确定性地映射到同一 openid，便于联调与自动化验证。
 *
 * 仅用于 CODE_PRESENT / AUTOMATED_VERIFIED 证据等级；真实 code2session 由 P2B（G0B 后）替换。
 */
@Component
public class StubWechatIdentityAdapter implements WechatIdentityPort {

    @Override
    public WechatSession codeToSession(String appid, String loginCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(appid.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '|');
            digest.update(loginCode.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().formatHex(digest.digest());
            return new WechatSession("stub-openid-" + hex.substring(0, 24),
                    "stub-unionid-" + hex.substring(24, 40));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
