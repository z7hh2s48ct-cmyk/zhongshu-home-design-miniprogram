package cn.iocoder.yudao.module.identity.wechat;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 微信身份 Stub：同一 login code 确定性地映射到同一 openid，便于联调与自动化验证。
 *
 * 装配条件（T13-01）：zhongshu.identity.wechat.provider=stub。真实 code2session 由 B1 的 real 实现替换；
 * 生产禁止 stub，由 ZhongshuWiringEnvironmentPostProcessor 启动守卫强制。
 *
 * 账号映射规则：
 * - `e2e-` 前缀 code：按 code 哈希独立账号（自动化套件每次运行需要全新账号，激活归属回归依赖此语义）；
 * - 其余 code：若配置了 stub-fixed-openid 则固定到同一开发账号——微信开发者工具每次编译都会产生
 *   全新 code，逐次建号会让交互联调在每次重启后丢失激活（UX 联调整改）；未配置时保持按 code 独立。
 */
@Component
@ConditionalOnProperty(prefix = "zhongshu.identity.wechat", name = "provider", havingValue = "stub")
public class StubWechatIdentityAdapter implements WechatIdentityPort {

    @Value("${zhongshu.identity.wechat.stub-fixed-openid:}")
    private String fixedOpenid;

    @Override
    public WechatSession codeToSession(String appid, String loginCode) {
        if (loginCode != null && !loginCode.startsWith("e2e-")
                && fixedOpenid != null && !fixedOpenid.isBlank()) {
            return new WechatSession(fixedOpenid, fixedOpenid + ":unionid");
        }
        return derive(appid, loginCode);
    }

    private WechatSession derive(String appid, String loginCode) {
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
