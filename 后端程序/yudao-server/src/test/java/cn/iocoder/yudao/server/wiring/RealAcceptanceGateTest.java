package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * T13-03 真实验收门禁模板：证明「真实检查默认不运行」，并作为 B1/B2/B4 真实验收测试的骨架。
 *
 * <p>双层门禁（防御纵深——任一层单独失效都不会触发真实外部消费）：
 * <ol>
 *   <li><b>构建层</b>：根 {@code pom.xml} 的 {@code maven-surefire-plugin} 默认
 *       {@code excludedGroups=${zs.test.excludedGroups}}（缺省值 {@code real-acceptance}），带本标签的
 *       测试不进入默认 {@code mvn test} 选择集；仅当显式 {@code -Dzs.test.excludedGroups=}（置空）时才纳入。</li>
 *   <li><b>用例层</b>：{@link EnabledIfEnvironmentVariable} 要求环境变量 {@code ZS_REAL_ACCEPTANCE=true}，
 *       缺少该开关（及后续各批要求的受限凭据）时本类整体禁用。</li>
 * </ol>
 *
 * <p>真实适配器（B1 微信 {@code code2session}、B2 腾讯云 COS、B4 微信支付）就绪后，按
 * 《T13-03 无真实消费测试基线与手工验收清单》在<b>批准的测试账号 / 素材 / 金额 / 成本范围内</b>补具体真实检查；
 * 在此之前即使两层门禁都被显式打开，本用例也以 {@link Assumptions#abort} 中止，绝不发起任何真实外部调用。
 *
 * <p>本类隔离测试无真实消费：默认构建下应「未被选中」，是 T13-03「真实检查默认不运行」的可执行证据。
 */
@Tag("real-acceptance")
@EnabledIfEnvironmentVariable(named = "ZS_REAL_ACCEPTANCE", matches = "true")
class RealAcceptanceGateTest {

    @Test
    void realAcceptanceIsOptInAndAbortsUntilAdaptersReady() {
        Assumptions.abort("真实验收需 ZS_REAL_ACCEPTANCE=true 且注入受限凭据；"
                + "真实适配器（B1/B2/B4）就绪前不执行任何真实外部调用。");
    }

}
