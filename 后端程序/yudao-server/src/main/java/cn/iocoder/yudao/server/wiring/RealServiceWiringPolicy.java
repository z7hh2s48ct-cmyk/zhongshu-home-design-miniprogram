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
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "众墅真实/Stub 实现选择配置校验失败（T13-01）：\n - " + String.join("\n - ", violations));
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
}
