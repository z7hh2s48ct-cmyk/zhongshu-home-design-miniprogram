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
}
