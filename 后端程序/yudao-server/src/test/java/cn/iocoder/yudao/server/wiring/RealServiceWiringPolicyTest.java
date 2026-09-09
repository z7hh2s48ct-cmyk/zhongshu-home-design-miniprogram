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

    /** 全部端口选择开发替身。 */
    private Map<String, String> allStub() {
        Map<String, String> p = new HashMap<>();
        p.put("zhongshu.identity.wechat.provider", "stub");
        p.put("zhongshu.commerce.payment.provider", "stub");
        p.put("zhongshu.design.asset.storage.provider", "local");
        p.put("zhongshu.design.asset.moderation.provider", "stub");
        return p;
    }

    /** 全部端口选择真实实现。 */
    private Map<String, String> allReal() {
        Map<String, String> p = new HashMap<>();
        p.put("zhongshu.identity.wechat.provider", "real");
        p.put("zhongshu.commerce.payment.provider", "real");
        p.put("zhongshu.design.asset.storage.provider", "cos");
        p.put("zhongshu.design.asset.moderation.provider", "real");
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
}
