package cn.iocoder.yudao.server.wiring;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;

/**
 * 众墅真实/Stub 实现装配策略（T13-01）。
 *
 * <p>每个外部服务端口通过一个 {@code provider} 配置键选择唯一实现，互斥取值保证「每端口只装配一个实现」：
 * <ul>
 *   <li>{@code zhongshu.identity.wechat.provider}：stub（开发替身）| real（B1 真实 code2session）</li>
 *   <li>{@code zhongshu.commerce.payment.provider}：stub | real（B4 微信支付）</li>
 *   <li>{@code zhongshu.design.asset.storage.provider}：local（本地文件）| cos（B2 腾讯云 COS）</li>
 *   <li>{@code zhongshu.design.asset.moderation.provider}：stub | real（后续批次真实内容审核）</li>
 * </ul>
 *
 * <p>本类是纯逻辑（无 Spring 依赖），便于隔离单测；启动期由
 * {@link ZhongshuWiringEnvironmentPostProcessor} 调用。任一端口 provider 缺失/非法，
 * 或生产 profile 下选择了开发替身（stub/local），立即抛出 {@link IllegalStateException} 令应用快速失败，
 * 杜绝「生产静默装配 Stub」与「缺失实现导致难以定位的 no-bean 报错」。
 *
 * <p>T13-02 在此之上补充「配置校验与脱敏」的校验侧：
 * <ul>
 *   <li>生产禁止启用开发便利 profile {@code zsdev}（携带公开占位密钥/种子数据/开发端点，对应 T12 B05）；</li>
 *   <li>生产必须提供已被消费的核心机密（激活码 pepper/制品密钥、Runtime 内部签名密钥），缺失即启动期快速失败，
 *       早于运行期 {@code AccessCodeCipher.requirePepper} 等使用处失败；</li>
 *   <li>「启用真实服务才要求相应密钥」：某端口选择真实实现时才校验其依赖的配置（微信 real 需要 appid 与 appsecret）。</li>
 * </ul>
 * 所有违例消息<b>只回显配置键名与环境变量名，绝不回显密钥值</b>，与「日志/管理端不泄露密钥」保持一致。
 * B2、B4 真实适配器落地时，在 {@link #REAL_MODE_REQUIREMENTS} 登记各自密钥键即可复用本校验。
 */
public final class RealServiceWiringPolicy {

    /** 生产 profile 标识：出现其一即视为生产，禁止装配开发替身实现。 */
    private static final List<String> PRODUCTION_PROFILES = List.of("prod", "production");

    /** 各端口的 provider 键、允许值、其中属于开发替身（生产禁用）的值、真实实现就绪批次提示。 */
    private static final List<PortProvider> PORTS = List.of(
            new PortProvider("zhongshu.identity.wechat.provider",
                    List.of("stub", "real"), List.of("stub"), "B1"),
            new PortProvider("zhongshu.commerce.payment.provider",
                    List.of("stub", "real"), List.of("stub"), "B4"),
            new PortProvider("zhongshu.design.asset.storage.provider",
                    List.of("local", "cos"), List.of("local"), "B2"),
            new PortProvider("zhongshu.design.asset.moderation.provider",
                    List.of("stub", "real"), List.of("stub"), "后续审核批次"));

    /** 生产禁止启用的开发便利 profile：携带公开占位密钥、种子数据与开发端点（对应 T12 B05「生产不得启用 zsdev」）。 */
    private static final List<String> PRODUCTION_FORBIDDEN_PROFILES = List.of("zsdev");

    /**
     * 生产必须提供、且当前已被代码消费的核心机密（值属机密，校验只回显键名/环境变量名，绝不回显值）。
     * 这些机密在 stub 与 real 模式下均被消费（激活码加密、Runtime 内部签名与 provider 无关），
     * 而生产必然运行真实服务，故生产必须齐备。B1/B2/B4 新增真实凭据键时在此登记。
     */
    private static final List<RequiredSecret> PRODUCTION_REQUIRED_SECRETS = List.of(
            new RequiredSecret("zhongshu.identity.access-code-pepper", "激活码 HMAC 派生（AccessCodeCipher）"),
            new RequiredSecret("zhongshu.identity.access-code-artifact-key", "激活码交付制品 AES-256-GCM 加密（AccessCodeCipher）"),
            new RequiredSecret("zhongshu.ai.internal-secret", "AI Runtime 内部接口签名（InternalSignatureVerifier）"));

    /**
     * 「启用真实实现才要求相应配置/密钥」的登记（T13-02 验收核心）。微信身份 real 需要 appid（AppAuthController 消费）
     * 与 appsecret（T13-04 RealWechatIdentityAdapter 的 code2session 消费）；此处只登记<b>已被消费</b>的键，
     * 不发明未消费变量（T12 约束）。B2（COS secret-id/secret-key/region/bucket）、B4（商户号/API v3 密钥/商户私钥）
     * 真实适配器落地时在此追加。
     */
    private static final List<RealModeRequirement> REAL_MODE_REQUIREMENTS = List.of(
            new RealModeRequirement("zhongshu.identity.wechat.provider", "real",
                    "zhongshu.identity.wechat-appid", "stub-appid", "B1"),
            // T13-04：real 微信身份还需 AppSecret（code2session 用）。AppSecret 无开发占位值（zsdev 默认留空），
            // 故 placeholderValue=null：validate() 仅要求非空（value.equals(null) 恒 false，自动跳过占位值比对）。
            new RealModeRequirement("zhongshu.identity.wechat.provider", "real",
                    "zhongshu.identity.wechat-appsecret", null, "B1"),
            // T13-21：real 微信支付需 merchant-id/merchant-serial-no/api-v3-key/merchant-private-key-path/notify-url
            // （WechatPaymentAdapter 构造期与 createPrepay 消费）。AppID 复用 B1 已登记的 wechat-appid（微信支付要求
            // 商户号绑定的 AppID 与登录 AppID 一致），不重复登记；五者均无开发占位值（zsdev/pg 默认留空），
            // 故 placeholderValue=null，validate() 仅要求非空。refund-notify-url 与 platform-cert-path 由 SDK 自动
            // 派生/下载覆盖，本期不单独登记。
            new RealModeRequirement("zhongshu.commerce.payment.provider", "real",
                    "zhongshu.commerce.payment.wechat.merchant-id", null, "B4"),
            new RealModeRequirement("zhongshu.commerce.payment.provider", "real",
                    "zhongshu.commerce.payment.wechat.merchant-serial-no", null, "B4"),
            new RealModeRequirement("zhongshu.commerce.payment.provider", "real",
                    "zhongshu.commerce.payment.wechat.api-v3-key", null, "B4"),
            new RealModeRequirement("zhongshu.commerce.payment.provider", "real",
                    "zhongshu.commerce.payment.wechat.merchant-private-key-path", null, "B4"),
            new RealModeRequirement("zhongshu.commerce.payment.provider", "real",
                    "zhongshu.commerce.payment.wechat.notify-url", null, "B4"));

    private RealServiceWiringPolicy() {
    }

    /**
     * 校验实现选择配置；违例聚合后一次性抛出，便于运维一次看全所有问题。
     *
     * @param effectiveProfiles 生效的 Spring profile（active 非空取 active，否则回退 default；用于判定是否生产）
     * @param propertyResolver  配置键读取函数（通常为 {@code environment::getProperty}）
     */
    public static void validate(Collection<String> effectiveProfiles, Function<String, String> propertyResolver) {
        boolean production = effectiveProfiles.stream().anyMatch(PRODUCTION_PROFILES::contains);
        List<String> violations = new ArrayList<>();
        for (PortProvider port : PORTS) {
            String raw = propertyResolver.apply(port.propertyKey());
            if (raw == null || raw.trim().isEmpty()) {
                violations.add(String.format(
                        "%s 未设置：必须显式选择实现（开发可用 %s；生产用 %s）。可通过 -D%s=%s 或环境变量 %s 指定。",
                        port.propertyKey(), port.devOnlyValues(), realValues(port),
                        port.propertyKey(), realValues(port).get(0), envName(port.propertyKey())));
            } else if (!port.allowedValues().contains(raw)) {
                // 不对 raw 做 trim：与 @ConditionalOnProperty(OnPropertyCondition) 对原始值的精确比较保持一致，
                // 避免 "stub " 等含首尾空白的值通过校验却选不中适配器，导致难以定位的 no-bean 失败（codex 评审 [P2]）。
                String hint = raw.equals(raw.trim()) ? "" : "（值含首尾空白，请去除后重试）";
                violations.add(String.format(
                        "%s='%s' 非法：允许值=%s。%s", port.propertyKey(), raw, port.allowedValues(), hint));
            } else if (production && port.devOnlyValues().contains(raw)) {
                violations.add(String.format(
                        "生产环境（生效 profile=%s）禁止使用开发替身：%s='%s'，请改为真实实现 %s（%s 就绪）。",
                        effectiveProfiles, port.propertyKey(), raw, realValues(port), port.realBatchHint()));
            }
        }

        // (2) 生产禁止开发便利 profile：zsdev 携带公开占位密钥/种子数据/开发端点，生产启用即等于用公开值冒充正式配置。
        if (production) {
            for (String forbidden : PRODUCTION_FORBIDDEN_PROFILES) {
                if (effectiveProfiles.contains(forbidden)) {
                    violations.add(String.format(
                            "生产环境（生效 profile=%s）禁止启用开发便利 profile '%s'：它携带公开占位密钥、种子数据与开发端点。"
                                    + "请移除 '%s'，改由密钥托管/环境变量提供正式配置。",
                            effectiveProfiles, forbidden, forbidden));
                }
            }
        }

        // (3) 生产必须齐备已消费的核心机密：缺失即在启动期快速失败，早于运行期使用处（如 AccessCodeCipher.requirePepper）。
        //     用 isBlank() 判定，与消费端（AccessCodeCipher / InternalSignatureVerifier 均用 isBlank）保持一致，
        //     令全角空白等 Unicode 空白也在启动期失败，避免「守卫放行但运行期仍拒绝」的不一致（codex 评审 [P2]）。
        //     只回显键名与环境变量名，绝不回显密钥值（与「日志/管理端不泄露密钥」一致）。
        if (production) {
            for (RequiredSecret secret : PRODUCTION_REQUIRED_SECRETS) {
                String value = propertyResolver.apply(secret.propertyKey());
                if (value == null || value.isBlank()) {
                    violations.add(String.format(
                            "生产环境缺少必需机密 %s（用途：%s）：必须由密钥托管/环境变量 %s 提供非空值（本校验不回显密钥值）。",
                            secret.propertyKey(), secret.purpose(), envName(secret.propertyKey())));
                }
            }
        }

        // (4) 启用真实服务才要求相应密钥/配置：仅当端口 provider=真实值时校验其依赖键。
        //     appid 非机密，占位值 stub-appid 可回显以助定位；真实机密一律不回显值。
        for (RealModeRequirement req : REAL_MODE_REQUIREMENTS) {
            if (!req.realValue().equals(propertyResolver.apply(req.providerKey()))) {
                continue;
            }
            String value = propertyResolver.apply(req.requiredKey());
            if (value == null || value.isBlank()) {
                violations.add(String.format(
                        "%s=%s（真实实现，%s 就绪）要求配置 %s：请由环境变量 %s 提供非空值。",
                        req.providerKey(), req.realValue(), req.batchHint(), req.requiredKey(), envName(req.requiredKey())));
            } else if (value.equals(req.placeholderValue())) {
                violations.add(String.format(
                        "%s=%s（真实实现，%s 就绪）要求真实的 %s：当前仍为开发占位值 '%s'，请通过环境变量 %s 替换为正式配置。",
                        req.providerKey(), req.realValue(), req.batchHint(), req.requiredKey(),
                        req.placeholderValue(), envName(req.requiredKey())));
            }
        }

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "众墅真实/Stub 实现选择与密钥配置校验失败（T13-01/T13-02）：\n - " + String.join("\n - ", violations));
        }
    }

    private static List<String> realValues(PortProvider port) {
        return port.allowedValues().stream().filter(v -> !port.devOnlyValues().contains(v)).toList();
    }

    /** 配置键 → 松散绑定环境变量名（点/横线转下划线并大写）。 */
    private static String envName(String propertyKey) {
        return propertyKey.replace('.', '_').replace('-', '_').toUpperCase();
    }

    private record PortProvider(String propertyKey, List<String> allowedValues,
                                List<String> devOnlyValues, String realBatchHint) {
    }

    /** 生产必须提供的已消费机密：配置键 + 用途说明（用于错误消息，不含值）。 */
    private record RequiredSecret(String propertyKey, String purpose) {
    }

    /** 真实实现依赖的配置要求：端口 provider 键、其真实值、要求的配置键、该键的开发占位值（无占位值时为 null，仅校验非空）、就绪批次提示。 */
    private record RealModeRequirement(String providerKey, String realValue, String requiredKey,
                                       String placeholderValue, String batchHint) {
    }
}
