package cn.iocoder.yudao.server.wiring;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * T13-30 后半：微信支付/整单退款「真实资金验收」的<b>脱敏证据载体</b>（脚手架）。
 *
 * <p>承 {@link RealAcceptanceGateTest} 双层门禁与《T13-30 管理端支付/退款权限矩阵》§7：真实资金验收
 * 必须留存证据（请求 ID / 测试订单号 / 渠道流水号 / 金额 / 时间戳 / 退款单号 / 渠道状态 / 截图引用），
 * 但<b>铁律 1「密钥/签名 URL 绝不进日志」同样约束证据</b>——证据会写入验收记录、PR 描述、 issue 附件，
 * 一旦夹带 APIv3 密钥、商户私钥、证书序列号或预签名 URL，即等同于凭据泄露。
 *
 * <p>本类以两道防线落实脱敏，且<b>无需任何真实凭据即可被 always-on 契约测试验证</b>
 * （{@link RealAcceptanceEvidenceTest}）：
 * <ol>
 *   <li><b>类型化安全字段</b>：只暴露承载业务事实的字段（订单号/流水号/金额分/时间戳/退款单号/渠道状态），
 *       <b>根本没有</b>可写入 APIv3/私钥/证书的字段面——凭据无处可放。</li>
 *   <li><b>自由文本红线净化</b>：唯一接受自由文本的 {@code notes} 与 {@code screenshotRefs} 在
 *       {@link #render()} 时经 {@link #redact(String)} 过滤，剥离私钥块、签名 URL 查询参数、
 *       {@code Authorization} 头、{@code apiV3Key=} 明文等模式，防止运维粘贴渠道原始响应时误带凭据。</li>
 * </ol>
 *
 * <p>{@link #render()} 输出为稳定多行文本，可安全写入日志/验收记录/PR。本类不含任何真实外部调用，
 * 是纯数据载体，故不受 {@code real-acceptance} 门禁约束（其消费方 {@code WechatPayRealAcceptanceTest} 才受门禁）。
 *
 * @author 众墅之家设计平台（T13-30 后半脚手架）
 * @see RealAcceptanceGateTest
 * @see RealAcceptanceEvidenceTest
 */
public final class RealAcceptanceEvidence {

    /** 固定渠道标识：T13-30 后半仅验收微信支付（B4 real）。 */
    private static final String CHANNEL = "WECHAT";

    /**
     * 自由文本红线净化模式（大小写不敏感）。命中即以 {@code [REDACTED]} 替换，绝不保留原值。
     *
     * <ul>
     *   <li>PEM 私钥/证书块：{@code -----BEGIN ... PRIVATE KEY-----} 至 {@code -----END ...-----}（含中间 base64）。</li>
     *   <li>签名 URL 查询参数：{@code signature=}/{@code X-Amz-Signature=}/{@code X-Amz-Credential=}/{@code key=} 等（COS/微信预签名常见）。</li>
     *   <li>{@code Authorization} 头与 {@code Bearer} 令牌。</li>
     *   <li>显式标注的 APIv3 密钥：{@code apiV3Key=}/{@code api-v3-key:} 等键值对。</li>
     * </ul>
     */
    private static final Pattern[] REDACTION_PATTERNS = {
            Pattern.compile("-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
            Pattern.compile("-----BEGIN CERTIFICATE-----[\\s\\S]*?-----END CERTIFICATE-----"),
            Pattern.compile("(?i)\\b(?:x-amz-signature|x-amz-credential|signature|sig|key)=[^&\\s]+"),
            // Authorization 头：消费到行尾（含 Bearer 令牌），避免 \\S+ 只吃到 "Bearer" 而残留真正的令牌
            Pattern.compile("(?i)\\bauthorization\\b[^\\n]*"),
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._\\-]+"),
            Pattern.compile("(?i)\\bapi[-_]?v3[-_]?key\\s*[:=]\\s*\\S+")
    };

    private final String requestId;
    private final String testOrderNo;
    private final String channelTransactionId;
    private final long amountCents;
    private final Instant paidAt;
    private final String refundId;
    private final String channelRefundId;
    private final String refundChannelState;
    private final Long netAmountCents;
    private final List<String> screenshotRefs;
    private final String notes;

    private RealAcceptanceEvidence(Builder b) {
        this.requestId = b.requestId;
        this.testOrderNo = b.testOrderNo;
        this.channelTransactionId = b.channelTransactionId;
        this.amountCents = b.amountCents;
        this.paidAt = b.paidAt;
        this.refundId = b.refundId;
        this.channelRefundId = b.channelRefundId;
        this.refundChannelState = b.refundChannelState;
        this.netAmountCents = b.netAmountCents;
        this.screenshotRefs = List.copyOf(b.screenshotRefs);
        this.notes = b.notes;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 剥离自由文本中的凭据模式。null/空白原样返回（无净化对象）。
     *
     * <p>净化是<b>保守删除</b>：命中即替换为 {@code [REDACTED]}，绝不尝试"部分保留"，
     * 与适配器 {@code readFailure} 只记异常类型名的净化纪律一致。
     */
    public static String redact(String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        String out = raw;
        for (Pattern p : REDACTION_PATTERNS) {
            out = p.matcher(out).replaceAll("[REDACTED]");
        }
        return out;
    }

    /**
     * 渲染为可安全写入日志/验收记录/PR 的多行文本。所有自由文本经 {@link #redact(String)} 净化。
     * 金额以「分」为整数记录（避免浮点），时间戳为 ISO-8601 UTC。
     */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append("[T13-30 真实资金验收证据]\n");
        sb.append("channel=").append(CHANNEL).append('\n');
        sb.append("requestId=").append(nullToDash(requestId)).append('\n');
        sb.append("testOrderNo=").append(nullToDash(testOrderNo)).append('\n');
        sb.append("channelTransactionId=").append(nullToDash(channelTransactionId)).append('\n');
        sb.append("amountCents=").append(amountCents).append('\n');
        sb.append("paidAt=").append(paidAt == null ? "-" : paidAt.toString()).append('\n');
        sb.append("refundId=").append(nullToDash(refundId)).append('\n');
        sb.append("channelRefundId=").append(nullToDash(channelRefundId)).append('\n');
        sb.append("refundChannelState=").append(nullToDash(refundChannelState)).append('\n');
        sb.append("netAmountCents=").append(netAmountCents == null ? "-" : netAmountCents.toString()).append('\n');
        sb.append("screenshotRefs=").append(redactJoin(screenshotRefs)).append('\n');
        sb.append("notes=").append(nullToDash(redact(notes)));
        return sb.toString();
    }

    private static String redactJoin(List<String> refs) {
        if (refs.isEmpty()) {
            return "-";
        }
        List<String> redacted = new ArrayList<>(refs.size());
        for (String ref : refs) {
            redacted.add(redact(ref));
        }
        return String.join(",", redacted);
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /** 证据构建器；字段全部可选，未设者 render() 以 {@code -} 占位。 */
    public static final class Builder {
        private String requestId;
        private String testOrderNo;
        private String channelTransactionId;
        private long amountCents;
        private Instant paidAt;
        private String refundId;
        private String channelRefundId;
        private String refundChannelState;
        private Long netAmountCents;
        private final List<String> screenshotRefs = new ArrayList<>();
        private String notes;

        public Builder requestId(String v) { this.requestId = v; return this; }
        public Builder testOrderNo(String v) { this.testOrderNo = v; return this; }
        public Builder channelTransactionId(String v) { this.channelTransactionId = v; return this; }
        public Builder amountCents(long v) { this.amountCents = v; return this; }
        public Builder paidAt(Instant v) { this.paidAt = v; return this; }
        public Builder refundId(String v) { this.refundId = v; return this; }
        public Builder channelRefundId(String v) { this.channelRefundId = v; return this; }
        public Builder refundChannelState(String v) { this.refundChannelState = v; return this; }
        public Builder netAmountCents(Long v) { this.netAmountCents = v; return this; }
        public Builder addScreenshotRef(String v) { this.screenshotRefs.add(v); return this; }
        public Builder notes(String v) { this.notes = v; return this; }

        public RealAcceptanceEvidence build() {
            return new RealAcceptanceEvidence(this);
        }
    }
}
