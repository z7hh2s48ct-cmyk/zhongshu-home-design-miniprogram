package cn.iocoder.yudao.module.commerce.payment.wechat;

import cn.iocoder.yudao.module.commerce.payment.PaymentPort;
import com.github.binarywang.wxpay.bean.notify.SignatureHeader;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyV3Result;
import com.github.binarywang.wxpay.bean.notify.WxPayRefundNotifyV3Result;
import com.github.binarywang.wxpay.bean.request.WxPayRefundV3Request;
import com.github.binarywang.wxpay.bean.request.WxPayUnifiedOrderV3Request;
import com.github.binarywang.wxpay.bean.result.WxPayOrderQueryV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayRefundQueryV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayRefundV3Result;
import com.github.binarywang.wxpay.bean.result.WxPayUnifiedOrderV3Result;
import com.github.binarywang.wxpay.bean.result.enums.TradeTypeEnum;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付真实适配器（T13-23 泳道 A）。
 *
 * <p>装配条件：{@code zhongshu.commerce.payment.provider=real}。与 {@link cn.iocoder.yudao.module.commerce.payment.StubPaymentPortAdapter}
 * 互斥（后者 {@code havingValue="stub"}）。生产环境由 {@code ZhongshuWiringEnvironmentPostProcessor} 启动守卫强制 real。
 *
 * <p>SDK：复用底座已锁定的 {@code com.github.binarywang:weixin-java-pay}（WxJava 生态，v3 API），
 * 不新增官方 {@code wechatpay-java} SDK（分派表 §1 SDK 选择决策）。
 *
 * <p>AppID 复用 B1 已登记的 {@code zhongshu.identity.wechat-appid}（微信支付要求商户号绑定的 AppID
 * 与登录 AppID 一致），本类不重复配置。
 *
 * <p>幂等：{@code createPrepay} 以 {@code orderNo} 为商户单号（微信侧幂等键）；同 orderNo 重复调用
 * 微信返回 {@code OUT_TRADE_NO_EXIST}，本适配器走 {@link #queryOrder} 兜底，禁止重复建业务订单
 * （分派表 §3.1 T13-23 ④）。
 *
 * <p>安全：私钥路径 / APIv3 密钥 / 证书序列号绝不进日志、异常消息或管理端（T13-02 红线）。
 * 异常消息只回显 {@code errCode}（微信公开错误码），不回显 {@code errCodeDes} 中可能含的敏感上下文。
 *
 * @author 众墅之家设计平台（T13-23）
 * @see WechatPayProperties
 * @see cn.iocoder.yudao.server.wiring.RealServiceWiringPolicy
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "zhongshu.commerce.payment", name = "provider", havingValue = "real")
@EnableConfigurationProperties(WechatPayProperties.class)
public class WechatPaymentAdapter implements PaymentPort {

    /** 渠道标识，写入 {@code payment_notification_inbox.channel} / {@code payment_transaction.channel} */
    private static final String CHANNEL = "WECHAT";

    /** 微信 v3  trade_state → 本系统 ChannelQueryResult.state 映射 */
    private static final Map<String, String> TRADE_STATE_MAP = Map.of(
            "SUCCESS", "SUCCEEDED",
            "NOTPAY", "PENDING",
            "USERPAYING", "PENDING",
            "CLOSED", "CLOSED",
            "REVOKED", "CLOSED",
            "PAYERROR", "FAILED"
    );

    /** 微信 v3  refund status → 本系统 ChannelRefundResult.state 映射 */
    private static final Map<String, String> REFUND_STATE_MAP = Map.of(
            "SUCCESS", "SUCCEEDED",
            "PROCESSING", "PROCESSING",
            "ABNORMAL", "FAILED",
            "CLOSED", "FAILED"
    );

    private final WechatPayProperties properties;
    private final String wechatAppId;
    private final WxPayService wxPayService;

    /**
     * Spring 装配构造器：从配置构建 {@link WxPayService}。
     *
     * @param properties  微信支付配置（T13-21 已登记五个必需键到 REAL_MODE_REQUIREMENTS）
     * @param wechatAppId 复用 B1 已登记的小程序 AppID
     */
    public WechatPaymentAdapter(WechatPayProperties properties,
                                @Value("${zhongshu.identity.wechat-appid}") String wechatAppId) {
        this.properties = properties;
        this.wechatAppId = wechatAppId;
        this.wxPayService = buildWxPayService(properties, wechatAppId);
    }

    /**
     * 测试构造器：注入 mock {@link WxPayService}，跳过真实 SDK 构建。
     * 仅供 {@code WechatPaymentAdapterTest} 使用（Mockito mock WxPayService）。
     */
    @VisibleForTesting
    WechatPaymentAdapter(WechatPayProperties properties, String wechatAppId, WxPayService wxPayService) {
        this.properties = properties;
        this.wechatAppId = wechatAppId;
        this.wxPayService = wxPayService;
    }

    /**
     * 构建 {@link WxPayService}（参考 {@code SocialClientServiceImpl.buildWxMpService} 模式）。
     *
     * <p>{@code certAutoUpdateTime=12}（小时）：SDK 自动下载并热更新平台证书，
     * 无需手动配置 {@code platformCertPath}（分派表 §6 说明）。
     */
    @VisibleForTesting
    static WxPayService buildWxPayService(WechatPayProperties props, String appId) {
        WxPayConfig config = new WxPayConfig();
        config.setAppId(appId);
        config.setMchId(props.getMerchantId());
        config.setCertSerialNo(props.getMerchantSerialNo());
        config.setApiV3Key(props.getApiV3Key());
        config.setPrivateKeyPath(props.getMerchantPrivateKeyPath());
        config.setNotifyUrl(props.getNotifyUrl());
        if (props.getRefundNotifyUrl() != null && !props.getRefundNotifyUrl().isBlank()) {
            config.setRefundNotifyUrl(props.getRefundNotifyUrl());
        }
        // 平台证书自动更新周期（小时）；SDK 首次调用时下载，之后按此周期刷新
        config.setCertAutoUpdateTime(12);
        WxPayServiceImpl service = new WxPayServiceImpl();
        service.setConfig(config);
        return service;
    }

    // ========== PaymentPort 实现 ==========

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public String merchantId() {
        return properties.getMerchantId();
    }

    /**
     * 预下单（JSAPI）：调 {@code WxPayService.createOrderV3(TradeTypeEnum.JSAPI, request)}，
     * 返回小程序 {@code wx.requestPayment} 所需的六个参数（appId/timeStamp/nonceStr/package/signType/paySign）。
     *
     * <p>幂等：同 {@code orderNo} 重复调用，微信返回 {@code OUT_TRADE_NO_EXIST} 或 {@code ORDERPAID}，
     * 本方法走 {@link #queryOrder} 兜底——若已支付则返回带 {@code state=ALREADY_PAID} 标记的 PrepayResult，
     * 调用方（{@code RechargePaymentService}）据此跳过重复建单（分派表 §3.1 T13-23 ④）。
     *
     * @param openid 微信小程序会话 openid（来源 {@code IdentitySessionPort.SessionContext}，禁止客户端传入）
     */
    @Override
    public PrepayResult createPrepay(String orderNo, long amountCents, String description, String openid) {
        WxPayUnifiedOrderV3Request request = new WxPayUnifiedOrderV3Request()
                .setAppid(wechatAppId)
                .setMchid(properties.getMerchantId())
                .setDescription(description)
                .setOutTradeNo(orderNo)
                .setNotifyUrl(properties.getNotifyUrl())
                .setAmount(new WxPayUnifiedOrderV3Request.Amount()
                        .setTotal((int) amountCents)
                        .setCurrency("CNY"))
                .setPayer(new WxPayUnifiedOrderV3Request.Payer().setOpenid(openid));
        try {
            // createOrderV3 对 JSAPI 返回 WxPayUnifiedOrderV3Result.JsapiResult（含已签名的 payParams）
            WxPayUnifiedOrderV3Result.JsapiResult jsapi =
                    wxPayService.createOrderV3(TradeTypeEnum.JSAPI, request);
            Map<String, String> callParams = new LinkedHashMap<>();
            callParams.put("appId", jsapi.getAppId());
            callParams.put("timeStamp", jsapi.getTimeStamp());
            callParams.put("nonceStr", jsapi.getNonceStr());
            callParams.put("package", jsapi.getPackageValue());
            callParams.put("signType", jsapi.getSignType());
            callParams.put("paySign", jsapi.getPaySign());
            return new PrepayResult(jsapi.getPrepayId(), callParams);
        } catch (WxPayException e) {
            String errCode = e.getErrCode();
            // 幂等兜底：商户单号已存在 / 订单已支付 → 查单确认状态，禁止重复建业务订单
            if ("OUT_TRADE_NO_EXIST".equals(errCode) || "ORDERPAID".equals(errCode)) {
                log.info("[createPrepay] 微信侧订单已存在，走查单兜底 orderNo={} errCode={}", orderNo, errCode);
                ChannelQueryResult query = queryOrder(orderNo);
                if ("SUCCEEDED".equals(query.getState())) {
                    // 已支付：返回带标记的 PrepayResult，调用方据此跳过重复建单
                    return new PrepayResult(query.getChannelTransactionId(),
                            Map.of("state", "ALREADY_PAID", "transactionId",
                                    query.getChannelTransactionId() != null ? query.getChannelTransactionId() : ""));
                }
                // 未支付但已存在（如 NOTPAY）：返回 PENDING 标记，调用方可引导用户继续支付
                return new PrepayResult(orderNo, Map.of("state", query.getState()));
            }
            // 其他错误：抛净化异常（不回显 errCodeDes 中可能含的敏感上下文）
            throw new IllegalStateException("微信支付预下单失败: errCode=" + errCode, e);
        }
    }

    /**
     * 主动查单：调 {@code WxPayService.queryOrderV3(outTradeNo, null)}，
     * 映射微信 {@code trade_state} → 本系统 {@code SUCCEEDED/PENDING/CLOSED/FAILED/UNKNOWN}。
     *
     * <p>微信 {@code ORDER_NOT_EXIST}（订单不存在）映射为 {@code UNKNOWN}——
     * 可能是预下单未到达微信，也可能是商户单号错误；调用方按 UNKNOWN 收口（审查 H1）。
     */
    @Override
    public ChannelQueryResult queryOrder(String orderNo) {
        try {
            WxPayOrderQueryV3Result result = wxPayService.queryOrderV3(orderNo, null);
            String state = TRADE_STATE_MAP.getOrDefault(result.getTradeState(), "UNKNOWN");
            Long amount = (result.getAmount() != null && result.getAmount().getTotal() != null)
                    ? result.getAmount().getTotal().longValue() : null;
            return new ChannelQueryResult(state, result.getTransactionId(), amount);
        } catch (WxPayException e) {
            if ("ORDER_NOT_EXIST".equals(e.getErrCode())) {
                return new ChannelQueryResult("UNKNOWN", null, null);
            }
            throw new IllegalStateException("微信支付查单失败: errCode=" + e.getErrCode(), e);
        }
    }

    /**
     * 校验通知真实性：检查微信 v3 签名头是否齐备。
     *
     * <p>实际密码学验签由 {@link #parseNotification} 内的 SDK {@code parseOrderNotifyV3Result} 完成；
     * 本方法只做轻量前置检查（头缺失 → 直接拒绝，不浪费 SDK 调用）。
     */
    @Override
    public boolean verifyNotification(Map<String, String> headers, byte[] body) {
        if (headers == null) {
            return false;
        }
        return headers.containsKey("Wechatpay-Signature")
                && headers.containsKey("Wechatpay-Timestamp")
                && headers.containsKey("Wechatpay-Nonce")
                && headers.containsKey("Wechatpay-Serial");
    }

    /**
     * 解析通知体：调 SDK {@code parseOrderNotifyV3Result}（内含验签 + APIv3 解密），
     * 返回规范化事件。{@code eventId} 取微信通知 {@code id}（全局唯一，供 Inbox UK 幂等）。
     */
    @Override
    public NormalizedNotification parseNotification(Map<String, String> headers, byte[] body) {
        SignatureHeader sigHeader = new SignatureHeader(
                headers.get("Wechatpay-Timestamp"),
                headers.get("Wechatpay-Nonce"),
                headers.get("Wechatpay-Signature"),
                headers.get("Wechatpay-Serial"));
        try {
            WxPayNotifyV3Result notifyResult = wxPayService.parseOrderNotifyV3Result(
                    new String(body, StandardCharsets.UTF_8), sigHeader);
            WxPayNotifyV3Result.DecryptNotifyResult decrypt = notifyResult.getResult();
            // eventId：优先取微信通知 id（全局唯一），回退 transactionId
            String eventId = (notifyResult.getRawData() != null && notifyResult.getRawData().getId() != null)
                    ? notifyResult.getRawData().getId()
                    : decrypt.getTransactionId();
            Instant paidAt = (decrypt.getSuccessTime() != null)
                    ? Instant.parse(decrypt.getSuccessTime()) : Instant.now();
            long amount = (decrypt.getAmount() != null && decrypt.getAmount().getTotal() != null)
                    ? decrypt.getAmount().getTotal().longValue() : 0L;
            return new NormalizedNotification(
                    eventId,
                    decrypt.getOutTradeNo(),
                    decrypt.getTransactionId(),
                    amount,
                    paidAt);
        } catch (WxPayException e) {
            // 验签失败 / 解密失败：抛净化异常（不回显密钥或证书内容）
            throw new IllegalStateException("微信支付通知解析失败: errCode=" + e.getErrCode(), e);
        }
    }

    /**
     * 整单全额退款：调 {@code WxPayService.refundV3(request)}。
     *
     * <p>P0 仅支持整单全额退款（分派表 §3.1 T13-28 ④）：{@code refund = total = amountCents}。
     * {@code outRefundNo} 由业务侧生成（{@code channelRefundId}），微信侧幂等。
     */
    @Override
    public ChannelRefundResult requestRefund(String orderNo, String channelRefundId, long amountCents) {
        WxPayRefundV3Request request = new WxPayRefundV3Request()
                .setOutTradeNo(orderNo)
                .setOutRefundNo(channelRefundId)
                .setAmount(new WxPayRefundV3Request.Amount()
                        .setRefund((int) amountCents)
                        .setTotal((int) amountCents)
                        .setCurrency("CNY"));
        if (properties.getRefundNotifyUrl() != null && !properties.getRefundNotifyUrl().isBlank()) {
            request.setNotifyUrl(properties.getRefundNotifyUrl());
        }
        try {
            WxPayRefundV3Result result = wxPayService.refundV3(request);
            return new ChannelRefundResult(REFUND_STATE_MAP.getOrDefault(result.getStatus(), "UNKNOWN"));
        } catch (WxPayException e) {
            throw new IllegalStateException("微信支付退款失败: errCode=" + e.getErrCode(), e);
        }
    }

    /**
     * 退款单查询：调 {@code WxPayService.refundQueryV3(outRefundNo)}。
     *
     * <p>审查 H1：{@code UNKNOWN} 收口依赖——{@code PROCESSING}/{@code UNKNOWN} 不得视为成功或失败
     * （分派表 §8 红线 5），由 {@code RefundRecoveryJob}（T13-29）定时查单直到终态。
     */
    @Override
    public ChannelRefundResult queryRefund(String orderNo, String channelRefundId) {
        try {
            WxPayRefundQueryV3Result result = wxPayService.refundQueryV3(channelRefundId);
            return new ChannelRefundResult(REFUND_STATE_MAP.getOrDefault(result.getStatus(), "UNKNOWN"));
        } catch (WxPayException e) {
            throw new IllegalStateException("微信支付退款查询失败: errCode=" + e.getErrCode(), e);
        }
    }

    /**
     * T13-28：解析退款通知体——调 SDK {@code parseRefundNotifyV3Result}（内含验签 + APIv3 解密）。
     *
     * <p>{@code eventId} 取微信通知 {@code id}（全局唯一，供 Inbox UK 幂等），缺失时回退 {@code refundId}；
     * {@code refund_status} 经 {@link #REFUND_STATE_MAP} 映射，未知值归 {@code UNKNOWN}（红线 5：
     * 不得当作成功或失败）。{@code refundedAt} 在渠道未返回 {@code success_time} 时为 null，
     * 不用 {@code Instant.now()} 伪造（与支付通知不同：退款时间仅供展示，不参与事实推进）。
     */
    @Override
    public NormalizedRefundNotification parseRefundNotification(Map<String, String> headers, byte[] body) {
        SignatureHeader sigHeader = new SignatureHeader(
                headers.get("Wechatpay-Timestamp"),
                headers.get("Wechatpay-Nonce"),
                headers.get("Wechatpay-Signature"),
                headers.get("Wechatpay-Serial"));
        try {
            WxPayRefundNotifyV3Result notifyResult = wxPayService.parseRefundNotifyV3Result(
                    new String(body, StandardCharsets.UTF_8), sigHeader);
            WxPayRefundNotifyV3Result.DecryptNotifyResult decrypt = notifyResult.getResult();
            String eventId = (notifyResult.getRawData() != null && notifyResult.getRawData().getId() != null)
                    ? notifyResult.getRawData().getId()
                    : decrypt.getRefundId();
            Instant refundedAt = (decrypt.getSuccessTime() != null)
                    ? Instant.parse(decrypt.getSuccessTime()) : null;
            long refundAmount = (decrypt.getAmount() != null && decrypt.getAmount().getRefund() != null)
                    ? decrypt.getAmount().getRefund().longValue() : 0L;
            return new NormalizedRefundNotification(
                    eventId,
                    decrypt.getOutTradeNo(),
                    decrypt.getOutRefundNo(),
                    REFUND_STATE_MAP.getOrDefault(decrypt.getRefundStatus(), "UNKNOWN"),
                    refundAmount,
                    refundedAt);
        } catch (WxPayException e) {
            // 验签失败 / 解密失败：抛净化异常（不回显密钥或证书内容）
            throw new IllegalStateException("微信退款通知解析失败: errCode=" + e.getErrCode(), e);
        }
    }
}
