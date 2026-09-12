package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4 T13-30 后半：{@link RealAcceptanceEvidence} 脱敏证据的<b>always-on 安全契约测试</b>。
 *
 * <p>本类<b>不带</b> {@code @Tag("real-acceptance")}、<b>不带</b> {@code @EnabledIfEnvironmentVariable}，
 * 故进入默认 {@code mvn test} 选择集——脱敏是「证据机制本身」的安全不变量，<b>无需任何真实凭据、无需真实资金动作</b>
 * 即可且必须被持续验证；一旦有人放宽 {@link RealAcceptanceEvidence#redact(String)} 或新增未净化的自由文本字段，
 * 本测试立即变红。这与真实资金验收（{@code WechatPayRealAcceptanceTest}，双层门禁默认不运行）刻意分离：
 * 前者护「机制不泄密」，后者护「真实动作不默认触发」。
 *
 * <p>覆盖铁律 1 在证据面的四类泄露载体：PEM 私钥/证书块、预签名 URL 查询参数、{@code Authorization}/{@code Bearer} 令牌、
 * 显式标注的 APIv3 密钥；并验证 {@link RealAcceptanceEvidence#render()} 对 {@code notes}/{@code screenshotRefs}
 * 两个自由文本入口强制净化。
 */
class RealAcceptanceEvidenceTest {

    /** 一个形似真实 APIv3 密钥的 32 位值（纯测试虚构，非任何真实凭据）。 */
    private static final String FAKE_API_V3_KEY = "0123456789abcdef0123456789abcdef";

    @Test
    @DisplayName("render 保留业务事实（渠道/订单号/流水号/金额分/退款状态），供验收对账")
    void renderKeepsBusinessFacts() {
        String out = RealAcceptanceEvidence.builder()
                .requestId("req-20260911-0001")
                .testOrderNo("test-order-0001")
                .channelTransactionId("4200001234202609110000000001")
                .amountCents(1L)
                .paidAt(Instant.parse("2026-09-11T02:00:00Z"))
                .refundId("refund-1")
                .channelRefundId("refund-1")
                .refundChannelState("SUCCEEDED")
                .netAmountCents(0L)
                .build()
                .render();

        assertThat(out)
                .contains("channel=WECHAT")
                .contains("testOrderNo=test-order-0001")
                .contains("channelTransactionId=4200001234202609110000000001")
                .contains("amountCents=1")
                .contains("paidAt=2026-09-11T02:00:00Z")
                .contains("refundChannelState=SUCCEEDED")
                .contains("netAmountCents=0");
    }

    @Test
    @DisplayName("redact 剥离 PEM 私钥块（含中间 base64），绝不残留")
    void redactStripsPrivateKeyBlock() {
        String raw = "验收备注 -----BEGIN PRIVATE KEY-----\nMIIEv" + FAKE_API_V3_KEY + "\n-----END PRIVATE KEY----- 结束";
        String out = RealAcceptanceEvidence.redact(raw);

        assertThat(out)
                .doesNotContain("BEGIN PRIVATE KEY")
                .doesNotContain("END PRIVATE KEY")
                .doesNotContain(FAKE_API_V3_KEY)
                .contains("[REDACTED]")
                .contains("验收备注"); // 非敏感上下文保留
    }

    @Test
    @DisplayName("redact 剥离预签名 URL 的签名查询参数（signature/X-Amz-Signature/key）")
    void redactStripsSignedUrlQueryParams() {
        String raw = "https://bucket.cos.ap-guangzhou.myqcloud.com/a.png?X-Amz-Signature="
                + FAKE_API_V3_KEY + "&X-Amz-Credential=AKIDxxx&key=secret-value&q=80";
        String out = RealAcceptanceEvidence.redact(raw);

        assertThat(out)
                .doesNotContain(FAKE_API_V3_KEY)
                .doesNotContain("secret-value")
                .doesNotContain("AKIDxxx")
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("redact 剥离 Authorization 头与 Bearer 令牌")
    void redactStripsAuthorizationAndBearer() {
        String out = RealAcceptanceEvidence.redact(
                "Authorization: Bearer eyJhbGciOi.secret.token 已调用");

        assertThat(out)
                .doesNotContain("eyJhbGciOi.secret.token")
                .doesNotContain("Bearer eyJ")
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("redact 剥离显式标注的 APIv3 密钥键值对")
    void redactStripsLabeledApiV3Key() {
        String out = RealAcceptanceEvidence.redact(
                "配置 apiV3Key=" + FAKE_API_V3_KEY + " 已注入；api-v3-key: " + FAKE_API_V3_KEY);

        assertThat(out)
                .doesNotContain(FAKE_API_V3_KEY)
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("render 对 notes 强制净化：即使误粘贴含私钥/签名 URL 的渠道原始响应也不泄密")
    void renderNeverLeaksSecretsCarriedInNotes() {
        String rawChannelDump = "渠道回调原文：signature=" + FAKE_API_V3_KEY
                + "，证书 -----BEGIN CERTIFICATE-----\nMIIDxTCCAq2g\n-----END CERTIFICATE-----";
        String out = RealAcceptanceEvidence.builder()
                .testOrderNo("test-order-0002")
                .amountCents(1L)
                .notes(rawChannelDump)
                .build()
                .render();

        assertThat(out)
                .contains("testOrderNo=test-order-0002") // 业务事实保留
                .doesNotContain(FAKE_API_V3_KEY)          // 密钥剥离
                .doesNotContain("BEGIN CERTIFICATE")      // 证书剥离
                .doesNotContain("MIIDxTCCAq2g")
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("render 对 screenshotRefs 强制净化：截图引用若夹带签名 URL 参数亦被剥离")
    void renderRedactsScreenshotRefs() {
        String out = RealAcceptanceEvidence.builder()
                .addScreenshotRef("artifacts/t13-30/pay-success.png")
                .addScreenshotRef("https://cdn.example.com/x.png?signature=" + FAKE_API_V3_KEY)
                .build()
                .render();

        assertThat(out)
                .contains("artifacts/t13-30/pay-success.png") // 普通路径引用保留
                .doesNotContain(FAKE_API_V3_KEY)              // 签名参数剥离
                .contains("[REDACTED]");
    }

    @Test
    @DisplayName("空证据 render 以 - 占位、不抛异常（脚手架未填充时亦可安全渲染）")
    void emptyEvidenceRendersWithPlaceholders() {
        String out = RealAcceptanceEvidence.builder().build().render();

        assertThat(out)
                .contains("channel=WECHAT")
                .contains("requestId=-")
                .contains("testOrderNo=-")
                .contains("netAmountCents=-");
    }
}
