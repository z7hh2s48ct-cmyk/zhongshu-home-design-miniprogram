package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T13-01 启动守卫装配路径回归（不启动完整应用、无 Docker、无真实消费）。
 *
 * <p>重点覆盖 codex 评审 [P1]：active profiles 为空时 Spring 回退到 default profiles，
 * 守卫必须据「生效 profile」判定生产，防止 {@code --spring.profiles.active=} 配合
 * {@code --spring.profiles.default=prod} 绕过「生产禁 Stub」校验。
 *
 * <p>B2 T13-10/11 追加：验证第二重守卫（{@link SensitiveLoggingPolicy}）确已挂到本启动路径上——
 * 否则它只是一个永远不会被调用的孤立类。细粒度判定见 {@link SensitiveLoggingPolicyTest}。
 */
class ZhongshuWiringEnvironmentPostProcessorTest {

    private final ZhongshuWiringEnvironmentPostProcessor postProcessor = new ZhongshuWiringEnvironmentPostProcessor();

    private StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        // 移除宿主的 systemProperties / systemEnvironment，令测试不受 CI 或本机 SPRING_PROFILES_ACTIVE 等影响（codex 复审 [P2]）
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("t1301-test", props));
        return env;
    }

    /** 全部端口选择开发替身（生产环境应被拒绝）。 */
    private Map<String, Object> allStubProviders() {
        Map<String, Object> p = new HashMap<>();
        p.put("zhongshu.identity.wechat.provider", "stub");
        p.put("zhongshu.commerce.payment.provider", "stub");
        p.put("zhongshu.design.asset.storage.provider", "local");
        p.put("zhongshu.design.asset.moderation.provider", "stub");
        return p;
    }

    @Test
    void defaultProfileProdWithStubProvidersFails() {
        // active 为空 → Spring 回退 default；default=prod 应视为生产，禁止 Stub（[P1] 核心回归）
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.default", "prod");
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产");
    }

    @Test
    void activeProfileProdWithStubProvidersFails() {
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.active", "prod");
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产");
    }

    @Test
    void nonProdDefaultWithStubProvidersPasses() {
        // 开发场景：active 为空、default 非生产 → 允许开发替身
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.default", "local");
        assertThatCode(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void sensitiveTransportLoggingLevelDebugFailsViaPostProcessor() {
        // provider 均合法（非生产 + Stub），唯一违例来自 logging.level.*：
        // 抛出敏感传输层专属消息即证明第二重守卫确已挂在启动路径上（B2 T13-10/11）
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.default", "local");
        props.put("logging.level.org.apache.hc.client5.http.wire", "DEBUG");
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("敏感传输层日志级别")
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void rootLoggingLevelDebugStillPassesViaPostProcessor() {
        // 鉴别力用例：挂载后仍须保留运维诊断能力。若守卫误拦 root，本用例变红
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.default", "local");
        props.put("logging.level.root", "DEBUG");
        assertThatCode(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .doesNotThrowAnyException();
    }

    @Test
    void providerViolationIsNotMaskedByLoggingGuard() {
        // 两重守卫共存时，provider 违例（优先级更高、先执行）仍须如实抛出，不被日志守卫掩盖
        Map<String, Object> props = allStubProviders();
        props.put("spring.profiles.active", "prod");
        props.put("logging.level.org.apache.hc.client5.http.wire", "DEBUG");
        assertThatThrownBy(() -> postProcessor.postProcessEnvironment(envWith(props), new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生产");
    }
}
