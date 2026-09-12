package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * B4 T13-30 后半：微信支付 + 整单退款「真实资金验收」脚手架（骨架，默认绝不运行）。
 *
 * <p>承 {@link RealAcceptanceGateTest} 通用门禁模板，落实《T13-30 管理端支付/退款权限矩阵》§7 的后半验收动作。
 * <b>三层门禁</b>（防御纵深——任一层单独失效都不会触发真实资金动作）：
 * <ol>
 *   <li><b>构建层</b>：{@code @Tag("real-acceptance")} 被根 {@code pom.xml} 的
 *       {@code maven-surefire-plugin excludedGroups=${zs.test.excludedGroups}}（缺省 {@code real-acceptance}）
 *       排除出默认 {@code mvn test}；仅显式 {@code -Dzs.test.excludedGroups=}（置空）才纳入。</li>
 *   <li><b>用例层</b>：{@code @EnabledIfEnvironmentVariable(ZS_REAL_ACCEPTANCE=true)}——缺开关则整类禁用。</li>
 *   <li><b>金额/批准层</b>：每个真实动作方法先过 {@link #requireApprovedAmountCents()} 与
 *       {@link #requireApprovalRef()}——未经<b>书面批准的测试金额</b>（{@code ZS_REAL_ACCEPTANCE_AMOUNT_CENTS}，
 *       硬上限 {@value #APPROVED_AMOUNT_CAP_CENTS} 分 = ¥0.01）与批准引用（{@code ZS_REAL_ACCEPTANCE_APPROVAL_REF}）
 *       即 {@link Assumptions#abort}，绝不发起真实支付。</li>
 * </ol>
 *
 * <p><b>凭据校验不在此重复</b>：{@code zhongshu.commerce.payment.provider=real} 时
 * merchant-id / merchant-serial-no / api-v3-key / merchant-private-key-path / notify-url 五键的非空校验，
 * 已由 B4 在 {@link RealServiceWiringPolicy#REAL_MODE_REQUIREMENTS} 登记、于 Spring 上下文启动期快速失败完成；
 * 本脚手架接线真实上下文时自动复用该守卫（AppID 复用 B1 {@code zhongshu.identity.wechat-appid}）。
 *
 * <p><b>当前为骨架</b>：三个真实资金动作方法在第 1/2/3 层门禁全开后，仍以 {@link Assumptions#abort} 停在
 * 「待接线真实 Spring 上下文」处——因为发起真实支付/退款需要 {@code @SpringBootTest} 全上下文 + 真实凭据 +
 * 真实 HTTPS 回调域名（EXT-03），这些外部前置未就绪（详见 runbook
 * 《项目文档/T13-30-后半-真实资金验收脚手架与证据模板-V1.0.md》）。届时把每个方法体内的 TODO 清单替换为真实驱动即可，
 * 证据统一用 {@link RealAcceptanceEvidence} 脱敏留存（其脱敏不变量由 always-on 的 {@link RealAcceptanceEvidenceTest} 守护）。
 *
 * @author 众墅之家设计平台（T13-30 后半脚手架）
 * @see RealAcceptanceGateTest
 * @see RealAcceptanceEvidence
 * @see RealServiceWiringPolicy
 */
@Tag("real-acceptance")
@EnabledIfEnvironmentVariable(named = "ZS_REAL_ACCEPTANCE", matches = "true")
class WechatPayRealAcceptanceTest {

    /** 书面批准的测试金额硬上限（分）：¥0.01 = 1 分。超过即 abort，杜绝误发起大额真实支付。 */
    private static final long APPROVED_AMOUNT_CAP_CENTS = 1L;

    @Test
    @DisplayName("真实支付→到账：payment_transaction.channel=WECHAT 且 merchant_id 为真商户号")
    void realPrepayThenNotificationRecordsWechatTransactionWithRealMerchantId() {
        long amountCents = requireApprovedAmountCents();
        requireApprovalRef();
        // TODO（外部前置就绪后接线，见 runbook §3 步骤 1-4）：
        //   1) @SpringBootTest 启动全上下文，provider=real（RealServiceWiringPolicy 校验五键通过）；
        //   2) RechargePaymentService.createOrder(userId, planId, idempotencyKey, openid) 建 ¥0.01 测试单；
        //   3) getPayParams → 真实小程序/JSAPI 拉起支付 → 真实付款 amountCents 分；
        //   4) 微信 /design/v1/payments/wechat/notify 回调 → handleNotification 落 payment_notification_inbox → processPaymentFact；
        //   断言：payment_transaction.channel='WECHAT'、merchant_id == 真商户号（非 'stub-merchant'）、amount_cents == amountCents、
        //         payment_state='PAID'、fulfillment 到账点数正确；证据用 RealAcceptanceEvidence 记录 requestId/testOrderNo/
        //         channelTransactionId/amountCents/paidAt（脱敏，绝不含 apiV3Key/私钥/签名 URL）。
        Assumptions.abort("T13-30 后半骨架：真实支付→到账验收待接线真实上下文（EXT-03 商户号/APIv3/证书/回调 HTTPS 域名 + B1 AppSecret）。"
                + "已确认批准金额=" + amountCents + " 分（≤" + APPROVED_AMOUNT_CAP_CENTS + "）。");
    }

    @Test
    @DisplayName("真实整单退款→冲正：refund_order.channel_state=SUCCEEDED 且点数冲正")
    void realFullRefundReversesPointsAndReachesSucceeded() {
        requireApprovedAmountCents();
        requireApprovalRef();
        // TODO（外部前置就绪后接线，见 runbook §3 步骤 5-7）：
        //   5) 对上一笔已 PAID 测试单发起整单退款：POST /recharge-orders/{orderId}/refund-requests（PAYMENT_RECONCILE 权限）；
        //   6) WechatPaymentAdapter.requestRefund → 微信 /refund-notify 回调 → handleRefundNotification → 查单收口 queryRefund；
        //   7) 点数冲正（PointReversalState）与预留释放；
        //   断言：refund_order.channel_state='SUCCEEDED'、退款金额==原支付金额（整单）、point reversal 完成、
        //         PROCESSING/UNKNOWN 绝不误判成功（分派表 §8 红线 5）；证据用 RealAcceptanceEvidence 记录 refundId/
        //         channelRefundId/refundChannelState（脱敏）。
        Assumptions.abort("T13-30 后半骨架：真实整单退款→冲正验收待接线真实上下文。");
    }

    @Test
    @DisplayName("净额≈0：全额退款后商户净收款归零（资金闭环校验）")
    void netAmountIsApproxZeroAfterFullRefund() {
        long amountCents = requireApprovedAmountCents();
        requireApprovalRef();
        // TODO（外部前置就绪后接线，见 runbook §3 步骤 8）：
        //   8) 收齐支付与退款事实后核对净额；
        //   断言：net = 已收 amountCents - 已退 amountCents ≈ 0（整单全额退款，允许渠道手续费差异，须书面记录）；
        //         证据用 RealAcceptanceEvidence.netAmountCents(0L) 记录，并在 notes 注明手续费口径（脱敏）。
        Assumptions.abort("T13-30 后半骨架：净额≈0 资金闭环校验待接线真实上下文（批准金额=" + amountCents + " 分）。");
    }

    // ===== 第 3 层门禁：批准金额 + 书面批准引用（缺失/超限即 abort，绝不发起真实资金动作）=====

    /**
     * 要求环境变量 {@code ZS_REAL_ACCEPTANCE_AMOUNT_CENTS} 存在、可解析、且 ≤ {@link #APPROVED_AMOUNT_CAP_CENTS}。
     * 任一不满足即 {@link Assumptions#abort}——这是「书面批准测试金额」在代码层的护栏。
     */
    private static long requireApprovedAmountCents() {
        String raw = System.getenv("ZS_REAL_ACCEPTANCE_AMOUNT_CENTS");
        if (raw == null || raw.isBlank()) {
            Assumptions.abort("缺 ZS_REAL_ACCEPTANCE_AMOUNT_CENTS（书面批准的测试金额，单位分）；未批准不发起真实支付。");
        }
        long cents;
        try {
            cents = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            Assumptions.abort("ZS_REAL_ACCEPTANCE_AMOUNT_CENTS 非整数分：" + raw);
            return -1; // 不可达（abort 抛 TestAbortedException）
        }
        if (cents <= 0 || cents > APPROVED_AMOUNT_CAP_CENTS) {
            Assumptions.abort("批准金额 " + cents + " 分超出硬上限 " + APPROVED_AMOUNT_CAP_CENTS
                    + " 分（¥0.01）；拒绝发起可能的大额真实支付。");
        }
        return cents;
    }

    /**
     * 要求环境变量 {@code ZS_REAL_ACCEPTANCE_APPROVAL_REF} 存在（书面批准引用，如审批单号/邮件主题/IM 记录链接）。
     * 缺失即 abort——真实资金动作必须可追溯到书面批准。
     */
    private static void requireApprovalRef() {
        String ref = System.getenv("ZS_REAL_ACCEPTANCE_APPROVAL_REF");
        if (ref == null || ref.isBlank()) {
            Assumptions.abort("缺 ZS_REAL_ACCEPTANCE_APPROVAL_REF（书面批准引用）；真实资金验收须可追溯到批准记录。");
        }
    }
}
