package cn.iocoder.yudao.server;

import cn.iocoder.yudao.module.commerce.payment.StubPaymentPortAdapter;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import cn.iocoder.yudao.module.identity.wechat.StubWechatIdentityAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13-01 装配合同：真实/Stub 由各端口 provider 键选择，每端口只装配一个实现，且可逐项独立切换。
 *
 * <p>选择机制由 {@code @Profile} 改为 {@code @ConditionalOnProperty}，故本测试直接注入 provider 配置验证装配结果。
 * 「生产禁止 Stub/本地实现」属于启动守卫职责，由 {@code RealServiceWiringPolicyTest} 覆盖。
 */
class DevelopmentAdaptersContractTest {

    private static final Class<?>[] ADAPTERS = {StubWechatIdentityAdapter.class, StubPaymentPortAdapter.class,
            LocalObjectStorageAdapter.class, StubContentModerationAdapter.class};

    /** zsdev.yaml 声明的 provider 默认值应恰好装配全部开发替身（证明出厂开发配置驱动装配）。 */
    @Test
    void zsdevProfileConfigAssemblesAllStubAdapters() throws IOException {
        List<PropertySource<?>> zsdev = new YamlPropertySourceLoader()
                .load("zsdev", new ClassPathResource("application-zsdev.yaml"));
        try (var context = new AnnotationConfigApplicationContext()) {
            zsdev.forEach(ps -> context.getEnvironment().getPropertySources().addFirst(ps));
            context.register(ADAPTERS);
            context.refresh();
            for (Class<?> adapter : ADAPTERS) {
                assertThat(context.getBeansOfType(adapter))
                        .as(adapter.getSimpleName() + " 应按 zsdev 默认 provider 装配").hasSize(1);
            }
        }
    }

    /** provider 未配置：任何替身都不装配（缺失即无实现，配合启动守卫快速失败，绝不静默降级）。 */
    @Test
    void unsetProvidersAssembleNoStubAdapter() {
        try (var context = contextWithProviders(Map.of())) {
            for (Class<?> adapter : ADAPTERS) {
                assertThat(context.getBeansOfType(adapter)).isEmpty();
            }
        }
    }

    /** provider=real/cos：开发替身不装配（真实实现由 B1/B2/B4 提供；选择互斥，绝不同时装配两套）。 */
    @Test
    void realProvidersDoNotAssembleStubAdapters() {
        try (var context = contextWithProviders(Map.of(
                "zhongshu.identity.wechat.provider", "real",
                "zhongshu.commerce.payment.provider", "real",
                "zhongshu.design.asset.storage.provider", "cos",
                "zhongshu.design.asset.moderation.provider", "real"))) {
            for (Class<?> adapter : ADAPTERS) {
                assertThat(context.getBeansOfType(adapter)).isEmpty();
            }
        }
    }

    /** 逐端口独立：仅微信切真实、其余保持替身——证明可逐项联调、互不影响。 */
    @Test
    void providersSelectPerPortIndependently() {
        try (var context = contextWithProviders(Map.of(
                "zhongshu.identity.wechat.provider", "real",
                "zhongshu.commerce.payment.provider", "stub",
                "zhongshu.design.asset.storage.provider", "local",
                "zhongshu.design.asset.moderation.provider", "stub"))) {
            assertThat(context.getBeansOfType(StubWechatIdentityAdapter.class)).isEmpty();
            assertThat(context.getBeansOfType(StubPaymentPortAdapter.class)).hasSize(1);
            assertThat(context.getBeansOfType(LocalObjectStorageAdapter.class)).hasSize(1);
            assertThat(context.getBeansOfType(StubContentModerationAdapter.class)).hasSize(1);
        }
    }

    private AnnotationConfigApplicationContext contextWithProviders(Map<String, Object> providers) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-providers", providers));
        context.register(ADAPTERS);
        context.refresh();
        return context;
    }
}
