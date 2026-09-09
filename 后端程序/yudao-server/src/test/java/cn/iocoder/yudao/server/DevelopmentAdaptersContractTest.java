package cn.iocoder.yudao.server;

import cn.iocoder.yudao.module.identity.wechat.StubWechatIdentityAdapter;
import cn.iocoder.yudao.module.commerce.payment.StubPaymentPortAdapter;
import cn.iocoder.yudao.module.design.asset.LocalObjectStorageAdapter;
import cn.iocoder.yudao.module.design.asset.StubContentModerationAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

class DevelopmentAdaptersContractTest {
    private static final Class<?>[] ADAPTERS = {StubWechatIdentityAdapter.class, StubPaymentPortAdapter.class,
            LocalObjectStorageAdapter.class, StubContentModerationAdapter.class};

    @Test
    void developmentProfileExplicitlyProvidesTestAdapters() {
        try (var context = context("pg", "zsdev")) {
            for (Class<?> adapter : ADAPTERS) assertThat(context.getBeansOfType(adapter)).hasSize(1);
        }
    }

    @Test
    void defaultAndProductionProfilesNeverProvideTestAdaptersEvenWithZsdev() {
        for (String[] profiles : new String[][]{{}, {"pg"}, {"prod"}, {"production"},
                {"pg", "zsdev", "prod"}, {"pg", "zsdev", "production"}}) {
            try (var context = context(profiles)) {
                for (Class<?> adapter : ADAPTERS) assertThat(context.getBeansOfType(adapter)).isEmpty();
            }
        }
    }

    private AnnotationConfigApplicationContext context(String... profiles) {
        var context = new AnnotationConfigApplicationContext();
        context.getEnvironment().setActiveProfiles(profiles);
        context.register(ADAPTERS);
        context.refresh();
        return context;
    }
}
