package cn.iocoder.yudao.module.commerce.payment.wechat;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 微信支付配置（T13-21）。
 *
 * <p>装配条件：{@code zhongshu.commerce.payment.provider=real} 时由 {@code WechatPaymentAdapter}
 * 通过 {@code @EnableConfigurationProperties(WechatPayProperties.class)} 显式启用；
 * {@code provider=stub} 时本类不被装配，{@link cn.iocoder.yudao.module.commerce.payment.StubPaymentPortAdapter}
 * 不消费本类字段，零副作用。
 *
 * <p>校验策略：非空校验由 {@link cn.iocoder.yudao.server.wiring.RealServiceWiringPolicy#REAL_MODE_REQUIREMENTS}
 * 在启动期完成（T13-21 已登记五个必需键），本类不重复校验；错误消息只回显键名与环境变量名，
 * <b>绝不回显任何密钥值</b>（与 T13-02 「日志/管理端不泄露密钥」一致）。
 *
 * <p>AppID 复用 B1 已登记的 {@code zhongshu.identity.wechat-appid}（微信支付要求商户号绑定的 AppID
 * 与登录 AppID 一致），本类不重复配置。
 *
 * <p>本期不纳入强校验的可选字段：{@code refundNotifyUrl}（退款回调 URL，未配置时由 SDK 从 {@code notifyUrl}
 * 派生或自动处理）、{@code platformCertPath}（平台证书路径，SDK 支持自动下载与热更新）。
 * 如后续需要独立配置，再按 T13-02 原则「只登记已被消费的键」追加到 {@code REAL_MODE_REQUIREMENTS}。
 *
 * @author 众墅之家设计平台（T13-21）
 * @see cn.iocoder.yudao.server.wiring.RealServiceWiringPolicy
 */
@Data
@ConfigurationProperties(prefix = "zhongshu.commerce.payment.wechat")
public class WechatPayProperties {

    /**
     * 微信支付商户号。
     * 消费点：{@code WechatPaymentAdapter} 构造期 → {@code WxPayConfig.setMchId()}。
     * 启动期校验：{@code RealServiceWiringPolicy.REAL_MODE_REQUIREMENTS} 要求非空。
     */
    private String merchantId;

    /**
     * 商户 API 证书序列号。
     * 消费点：{@code WxPayConfig.setCertSerialNo()}，用于请求签名时标识商户证书。
     * 启动期校验：要求非空。
     */
    private String merchantSerialNo;

    /**
     * APIv3 密钥（32 字节），回调通知解密用。
     * 消费点：{@code WxPayConfig.setApiV3Key()}；回调通知入口用其解密 resource 字段。
     * 启动期校验：要求非空；错误消息不回显值。
     */
    private String apiV3Key;

    /**
     * 商户私钥文件路径（PEM 格式）。
     * 支持 {@code classpath:} 与 {@code file:} 前缀；私钥内容绝不进日志、异常消息或管理端。
     * 消费点：{@code WxPayConfig.setPrivateKeyPath()}，用于请求签名。
     * 启动期校验：要求非空（路径字符串非空，不校验文件是否存在——SDK 加载期失败时由适配器转为净化异常）。
     */
    private String merchantPrivateKeyPath;

    /**
     * 支付回调 HTTPS URL。
     * 消费点：{@code WechatPaymentAdapter.createPrepay()} → {@code WxPayUnifiedOrderV3Request.setNotifyUrl()}。
     * 必须为公网可访问的 HTTPS 域名，且路径与 {@code WechatPayNotifyController} 的支付通知入口一致
     * （{@code /design/v1/payments/wechat/notify}）。
     * 启动期校验：要求非空。
     */
    private String notifyUrl;

    /**
     * 退款回调 HTTPS URL（可选）。
     * 未配置时 SDK 使用 {@code notifyUrl} 派生或自动处理；本期不纳入 {@code REAL_MODE_REQUIREMENTS} 强校验。
     * 路径应与 {@code WechatPayNotifyController} 的退款通知入口一致
     * （{@code /design/v1/payments/wechat/refund-notify}）。
     */
    private String refundNotifyUrl;
}
