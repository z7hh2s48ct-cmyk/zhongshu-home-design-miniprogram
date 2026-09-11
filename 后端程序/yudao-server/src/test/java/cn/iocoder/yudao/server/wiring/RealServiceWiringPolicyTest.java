package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13-01 装配策略隔离单测（无 Spring 上下文、无 Docker、无真实消费）：
 * 覆盖 provider 缺失/空白/非法、生产禁 Stub/local、开发与生产正确配置放行、违例聚合。
 */
class RealServiceWiringPolicyTest {

    private static final List<String> DEV = List.of("local", "pg", "zsdev");
    private static final List<String> PROD = List.of("local", "pg", "prod");
    private static final String PEPPER_VALUE = "test-pepper-0123456789abcdef-0123456789";

    /** 全部端口选择开发替身。 */
    private Map<String, String> allStub() {
        Map<String, String> p = new HashMap<>();
        p.put("zhongshu.identity.wechat.provider", "stub");
        p.put("zhongshu.commerce.payment.provider", "stub");
        p.put("zhongshu.design.asset.storage.provider", "local");
        p.put("zhongshu.design.asset.moderation.provider", "stub");
        return p;
    }

    /** 全部端口选择真实实现，并齐备真实实现所需的核心机密、appid 与 appsecret（生产放行的完整正确配置）。 */
    private Map<String, String> allReal() {
        Map<String, String> p = new HashMap<>();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.commerce.payment.provider", "real");
        p.put("zhongshu.design.asset.storage.provider", "cos");
        p.put("zhongshu.design.asset.moderation.provider", "real");
        // T13-02/T13-04：真实实现所需的已消费机密、appid 与 appsecret（值仅测试用虚构凭据，非真实密钥）
        p.put("zhongshu.identity.access-code-pepper", PEPPER_VALUE);
        p.put("zhongshu.identity.access-code-artifact-key", "test-artifact-key-base64-32bytes!!!!!!");
        p.put("zhongshu.ai.internal-secret", "test-internal-secret-0123456789abcdef");
        p.put("zhongshu.identity.wechat-appid", "wx-test-real-appid");
        // T13-04：real 微信身份还需 appsecret（code2session 消费）
        p.put("zhongshu.identity.wechat-appsecret", "test-real-appsecret");
        // T13-21：B4 微信支付 real 模式所需 merchant-id/merchant-serial-no/api-v3-key/merchant-private-key-path/notify-url
        // （值仅测试用虚构凭据，非真实密钥；AppID 复用 zhongshu.identity.wechat-appid，不重复登记）
        p.put("zhongshu.commerce.payment.wechat.merchant-id", "test-mch-1234567890");
        p.put("zhongshu.commerce.payment.wechat.merchant-serial-no", "test-serial-0123456789ABCDEF");
        p.put("zhongshu.commerce.payment.wechat.api-v3-key", "test-api-v3-key-32bytes-long!!!!");
        p.put("zhongshu.commerce.payment.wechat.merchant-private-key-path", "classpath:test-only/apiclient_key.pem");
        p.put("zhongshu.commerce.payment.wechat.notify-url", "https://test.example.com/design/v1/payments/wechat/notify");
        return p;
    }

    @Test
    void devWithStubProvidersPasses() {
        assertThatCode(() -> RealServiceWiringPolicy.validate(DEV, allStub()::get)).doesNotThrowAnyException();
    }

    @Test
    void prodWithRealProvidersPasses() {
        assertThatCode(() -> RealServiceWiringPolicy.validate(PROD, allReal()::get)).doesNotThrowAnyException();
    }

    @Test
    void prodWithAnyStubProviderFails() {
        Map<String, String> p = allReal();
        p.put("zhongshu.commerce.payment.provider", "stub"); // 支付退回替身
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.commerce.payment.provider")
                .hasMessageContaining("生产");
    }

    @Test
    void prodWithLocalStorageFails() {
        Map<String, String> p = allReal();
        p.put("zhongshu.design.asset.storage.provider", "local");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(List.of("production"), p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.design.asset.storage.provider");
    }

    @Test
    void missingProviderKeyFails() {
        Map<String, String> p = allStub();
        p.remove("zhongshu.identity.wechat.provider");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat.provider")
                .hasMessageContaining("未设置");
    }

    @Test
    void blankProviderValueFails() {
        Map<String, String> p = allStub();
        p.put("zhongshu.commerce.payment.provider", "   ");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.commerce.payment.provider");
    }

    @Test
    void unknownProviderValueFails() {
        Map<String, String> p = allStub();
        p.put("zhongshu.design.asset.storage.provider", "s3"); // 非允许值
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法")
                .hasMessageContaining("zhongshu.design.asset.storage.provider");
    }

    @Test
    void aggregatesAllViolationsInOneMessage() {
        Map<String, String> empty = new HashMap<>(); // 全部缺失
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, empty::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat.provider")
                .hasMessageContaining("zhongshu.commerce.payment.provider")
                .hasMessageContaining("zhongshu.design.asset.storage.provider")
                .hasMessageContaining("zhongshu.design.asset.moderation.provider");
    }

    @Test
    void paddedProviderValueRejected() {
        // codex 评审 [P2]：带首尾空白的值不再被 trim 放行——与 @ConditionalOnProperty 对原始值的精确比较一致，
        // 避免 "stub " 通过校验却选不中适配器，导致难以定位的 no-bean 失败。
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "stub ");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("非法")
                .hasMessageContaining("zhongshu.identity.wechat.provider");
    }

    // ===== T13-02：配置校验（生产禁 zsdev、生产必需机密、真实实现依赖 appid、消息不回显密钥值） =====

    @Test
    void prodWithZsdevProfileFails() {
        // 生产启用开发便利 profile zsdev（携带公开占位密钥/种子数据）必须被拒绝（T12 B05）
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(List.of("prod", "pg", "zsdev"), allReal()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zsdev")
                .hasMessageContaining("生产");
    }

    @Test
    void prodMissingCoreSecretFails() {
        Map<String, String> p = allReal();
        p.remove("zhongshu.identity.access-code-pepper");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.access-code-pepper")
                .hasMessageContaining("生产");
    }

    @Test
    void prodBlankCoreSecretFails() {
        Map<String, String> p = allReal();
        p.put("zhongshu.ai.internal-secret", "   "); // 空白等同缺失
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.ai.internal-secret");
    }

    @Test
    void prodUnicodeWhitespaceSecretFails() {
        // codex 评审 [P2]：全角空白（\u3000）isBlank()=true，应与消费端 InternalSignatureVerifier.isBlank() 一致地在启动期失败（trim() 会漏掉）
        Map<String, String> p = allReal();
        p.put("zhongshu.ai.internal-secret", "\u3000");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.ai.internal-secret");
    }

    @Test
    void errorMessageNeverEchoesSecretValue() {
        // 缺失某机密时，聚合消息只回显键名，绝不回显其它已配置机密的值
        Map<String, String> p = allReal();
        p.put("zhongshu.identity.access-code-pepper", PEPPER_VALUE);
        p.put("zhongshu.identity.access-code-artifact-key", ""); // 触发违例
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(PROD, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.access-code-artifact-key")
                .hasMessageNotContaining(PEPPER_VALUE);
    }

    @Test
    void realWechatProviderRequiresAppid() {
        // 启用真实微信身份才要求 appid（任意 profile 均适用；开发环境同样需要真实 appid）
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "real");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat-appid");
    }

    @Test
    void realWechatProviderRejectsPlaceholderAppid() {
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.identity.wechat-appid", "stub-appid"); // 开发占位值
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat-appid")
                .hasMessageContaining("stub-appid");
    }

    @Test
    void devRealWechatWithAppidAndSecretPasses() {
        // 开发环境启用真实微信身份并给出非占位 appid + 非空 appsecret，其余端口仍用开发替身：放行
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.identity.wechat-appid", "wx-dev-real-appid");
        p.put("zhongshu.identity.wechat-appsecret", "dev-real-appsecret");
        assertThatCode(() -> RealServiceWiringPolicy.validate(DEV, p::get)).doesNotThrowAnyException();
    }

    @Test
    void realWechatProviderRequiresAppsecret() {
        // T13-04：启用真实微信身份但缺 appsecret（code2session 必需）→ 快速失败
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.identity.wechat-appid", "wx-dev-real-appid");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat-appsecret");
    }

    @Test
    void realWechatProviderRejectsBlankAppsecret() {
        // T13-04：appsecret 为空白等同缺失（appsecret 无开发占位值，仅校验非空）
        Map<String, String> p = allStub();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.identity.wechat-appid", "wx-dev-real-appid");
        p.put("zhongshu.identity.wechat-appsecret", "   ");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.identity.wechat-appsecret");
    }



    // ===== T13-21：B4 微信支付 real 模式依赖配置（merchant-id/serial-no/api-v3-key/private-key-path/notify-url，消息不回显密钥值） =====

    @Test
    void realWechatPayProviderRequiresSecrets() {
        // T13-21：启用 real 微信支付但缺 B4 五个配置键 → 快速失败（AppID 复用 B1 已登记的 wechat-appid，此处不重复登记）
        Map<String, String> p = allStub();
        p.put("zhongshu.commerce.payment.provider", "real");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.commerce.payment.wechat.merchant-id")
                .hasMessageContaining("zhongshu.commerce.payment.wechat.merchant-serial-no")
                .hasMessageContaining("zhongshu.commerce.payment.wechat.api-v3-key")
                .hasMessageContaining("zhongshu.commerce.payment.wechat.merchant-private-key-path")
                .hasMessageContaining("zhongshu.commerce.payment.wechat.notify-url");
    }

    @Test
    void realWechatPayProviderRejectsBlankApiV3Key() {
        // T13-21：api-v3-key 为空白等同缺失（无开发占位值，仅校验非空）
        Map<String, String> p = allStub();
        p.put("zhongshu.commerce.payment.provider", "real");
        p.put("zhongshu.commerce.payment.wechat.merchant-id", "test-mch-1234567890");
        p.put("zhongshu.commerce.payment.wechat.merchant-serial-no", "test-serial-0123456789ABCDEF");
        p.put("zhongshu.commerce.payment.wechat.api-v3-key", "   ");
        p.put("zhongshu.commerce.payment.wechat.merchant-private-key-path", "classpath:test-only/apiclient_key.pem");
        p.put("zhongshu.commerce.payment.wechat.notify-url", "https://test.example.com/design/v1/payments/wechat/notify");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.commerce.payment.wechat.api-v3-key");
    }

    @Test
    void devRealWechatPayWithAllKeysPasses() {
        // 开发环境启用 real 微信支付并给出全部必需配置，其余端口仍用开发替身：放行
        Map<String, String> p = allStub();
        p.put("zhongshu.commerce.payment.provider", "real");
        p.put("zhongshu.commerce.payment.wechat.merchant-id", "test-mch-1234567890");
        p.put("zhongshu.commerce.payment.wechat.merchant-serial-no", "test-serial-0123456789ABCDEF");
        p.put("zhongshu.commerce.payment.wechat.api-v3-key", "test-api-v3-key-32bytes-long!!!!");
        p.put("zhongshu.commerce.payment.wechat.merchant-private-key-path", "classpath:test-only/apiclient_key.pem");
        p.put("zhongshu.commerce.payment.wechat.notify-url", "https://test.example.com/design/v1/payments/wechat/notify");
        assertThatCode(() -> RealServiceWiringPolicy.validate(DEV, p::get)).doesNotThrowAnyException();
    }

    @Test
    void wechatPayErrorMessageNeverEchoesSecretKeyValue() {
        // T13-21：缺 merchant-id 触发违例时，聚合消息只回显键名，绝不回显已配置的 api-v3-key 与 private-key-path 值
        Map<String, String> p = allStub();
        p.put("zhongshu.commerce.payment.provider", "real");
        // 故意缺 merchant-id 触发违例
        p.put("zhongshu.commerce.payment.wechat.merchant-serial-no", "test-serial-0123456789ABCDEF");
        p.put("zhongshu.commerce.payment.wechat.api-v3-key", "SUPER-SECRET-APIV3-KEY-DO-NOT-ECHO");
        p.put("zhongshu.commerce.payment.wechat.merchant-private-key-path", "file:///abs/path/SUPER-SECRET-KEY-DO-NOT-ECHO.pem");
        p.put("zhongshu.commerce.payment.wechat.notify-url", "https://test.example.com/design/v1/payments/wechat/notify");
        assertThatThrownBy(() -> RealServiceWiringPolicy.validate(DEV, p::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zhongshu.commerce.payment.wechat.merchant-id")
                .hasMessageNotContaining("SUPER-SECRET-APIV3-KEY-DO-NOT-ECHO")
                .hasMessageNotContaining("SUPER-SECRET-KEY-DO-NOT-ECHO");
    }
}
