package cn.iocoder.yudao.module.commerce.controller.notify;

import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.servlet.ServletUtils;
import cn.iocoder.yudao.framework.ratelimiter.core.annotation.RateLimiter;
import cn.iocoder.yudao.framework.ratelimiter.core.keyresolver.impl.ClientIpRateLimiterKeyResolver;
import cn.iocoder.yudao.module.commerce.payment.RechargePaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.annotation.security.PermitAll;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 微信支付 v3 通知入口（T13-25 · B 泳道）。
 *
 * <p><b>响应契约</b>：微信要求 HTTP 200 + 特定 JSON 体
 * {@code {"code":"SUCCESS","message":"成功"}}（禁止用 {@code CommonResult}，
 * 分派表 §8 红线 6）；失败返回 HTTP 4xx/5xx + {@code {"code":"FAIL","message":"..."}}，
 * 微信将按 15s / 15s / 30s / 3m / 10m / 20m / 30m / 30m / 30m / 60m / 3h / 6h 退避重试 8 次。
 *
 * <p><b>请求体缓存</b>：复用框架 {@code CacheRequestBodyFilter}（分派表 §3.2 ②"如已有则复用"），
 * 通过 {@link ServletUtils#getBodyBytes(HttpServletRequest)} 获取原始字节供验签，
 * 禁止 {@code new BufferedReader(request.getReader())} 直接读流（会耗尽 InputStream 导致下游无法二次读取）。
 *
 * <p><b>请求体大小限</b>：≤ 64KB（分派表 §3.2 ④），Content-Length 预检 + 读入后二次校验。
 *
 * <p><b>限流</b>：独立限流不复用业务端点配额（分派表 §3.2 ①），
 * 按 ClientIp 计数，1000/秒/IP（微信回调源 IP 池 ≥ 20，总吞吐 ≥ 20k/s，远高于业务峰值）。
 *
 * <p><b>安全边界</b>：
 * <ul>
 *   <li>{@code @PermitAll} 放行 Spring Security 底座（微信回调无 Authorization 头）</li>
 *   <li>验签在 {@link RechargePaymentService#handleNotification} 内通过
 *       {@code PaymentPort.verifyNotification} 完成，缺 4 个 Wechat-* 头直接 400 拒绝，
 *       禁止把「签名头缺失」当"验签失败"传给下游（会导致 stub 与 real 行为不一致）</li>
 *   <li>退款通知路径 T13-25 阶段占位，实际处理留给 T13-28（{@code RechargePaymentService}
 *       尚无 {@code handleRefundNotification} 方法）</li>
 * </ul>
 *
 * @see RechargePaymentService#handleNotification(Map, byte[])
 */
@Slf4j
@Tag(name = "微信支付通知入口（T13-25 · B 泳道）")
@RestController
@RequestMapping("/design/v1/payments/wechat")
@PermitAll
public class WechatPayNotifyController {

    /** 微信 v3 通知请求体大小上限（分派表 §3.2 ④：≤ 64KB） */
    static final int MAX_BODY_SIZE = 64 * 1024;

    /** 微信要求的成功响应体（禁止用 CommonResult，分派表 §8 红线 6） */
    private static final Map<String, String> SUCCESS_BODY = Map.of("code", "SUCCESS", "message", "成功");

    /** 微信 v3 签名头名（缺任意一个直接拒绝，禁止把「头缺失」当"验签失败"传给下游） */
    private static final String HEADER_TIMESTAMP = "Wechatpay-Timestamp";
    private static final String HEADER_NONCE = "Wechatpay-Nonce";
    private static final String HEADER_SIGNATURE = "Wechatpay-Signature";
    private static final String HEADER_SERIAL = "Wechatpay-Serial";

    @Resource
    private RechargePaymentService rechargePaymentService;

    /**
     * 支付结果通知入口。
     *
     * <p>微信在用户完成支付后 POST 到本端点，body 为加密的 JSON（含 {@code resource.ciphertext}），
     * 4 个 {@code Wechatpay-*} 头携带签名。Controller 只做前置校验（大小 / 头齐备），
     * 验签解密 + Inbox 幂等 + 到账处理全部委托给 {@link RechargePaymentService#handleNotification}。
     */
    @PostMapping(value = "/notify", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(time = 1, count = 1000, keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "微信支付结果通知（T13-25 · 独立限流 1000/s/IP）")
    public ResponseEntity<Map<String, String>> handlePaymentNotify(HttpServletRequest request) {
        return handleNotify(request, false);
    }

    /**
     * 退款结果通知入口（T13-28）。
     *
     * <p>与支付通知共用同一套前置校验（大小 ≤ 64KB / 4 个签名头齐备）与限流策略，
     * 验签解密 + Inbox 幂等 + 冲正/释放全部委托给
     * {@link RechargePaymentService#handleRefundNotification}。渠道返回 PROCESSING/UNKNOWN 时
     * 不做任何资金动作（分派表 §8 红线 5），由 {@code RefundRecoveryJob}（T13-29）查单收口。
     */
    @PostMapping(value = "/refund-notify", produces = MediaType.APPLICATION_JSON_VALUE)
    @RateLimiter(time = 1, count = 1000, keyResolver = ClientIpRateLimiterKeyResolver.class)
    @Operation(summary = "微信退款结果通知（T13-28 · 独立限流 1000/s/IP）")
    public ResponseEntity<Map<String, String>> handleRefundNotify(HttpServletRequest request) {
        return handleNotify(request, true);
    }

    /**
     * 统一处理支付/退款通知：前置校验 → 提取头 → 分流到对应领域方法。
     *
     * @param request  HttpServletRequest（body 已由 CacheRequestBodyFilter 缓存）
     * @param isRefund true = 退款通知（T13-28），false = 支付通知
     */
    private ResponseEntity<Map<String, String>> handleNotify(HttpServletRequest request, boolean isRefund) {
        String uri = request.getRequestURI();
        // 日志用通知类型（“支付”/“退款”），避免两条链路日志混淆
        String kind = isRefund ? "退款" : "支付";

        // 1. Content-Length 预检（不读入内存即可拒绝超大请求，防 OOM）
        long contentLength = request.getContentLengthLong();
        if (contentLength > MAX_BODY_SIZE) {
            log.warn("[微信通知] 请求体超限（Content-Length）: size={}B, uri={}", contentLength, uri);
            return failResponse(HttpStatus.PAYLOAD_TOO_LARGE, "请求体超限");
        }

        // 2. 读取原始 body（依赖框架 CacheRequestBodyFilter + ServletUtils.getBodyBytes）
        byte[] body = ServletUtils.getBodyBytes(request);
        if (body == null || body.length == 0) {
            // 非 JSON Content-Type 或 body 为空：微信通知必须是 application/json，拒绝
            log.warn("[微信通知] 请求体为空或非 JSON: contentType={}, uri={}",
                    request.getContentType(), uri);
            return failResponse(HttpStatus.BAD_REQUEST, "请求体为空或非 JSON");
        }
        if (body.length > MAX_BODY_SIZE) {
            // 二次校验：Content-Length 可能缺失或被伪造
            log.warn("[微信通知] 请求体实际超限: size={}B, uri={}", body.length, uri);
            return failResponse(HttpStatus.PAYLOAD_TOO_LARGE, "请求体超限");
        }

        // 3. 提取微信 v3 签名头（4 个必需头；缺任意一个直接拒绝，禁止把「头缺失」传给下游当"验签失败"）
        Map<String, String> headers = extractWechatHeaders(request);
        if (headers.isEmpty()) {
            log.warn("[微信通知] 缺少签名头: uri={}", uri);
            return failResponse(HttpStatus.BAD_REQUEST, "缺少签名头");
        }

        // 4. 分流：支付 → handleNotification；退款 → handleRefundNotification（T13-28）
        try {
            String result = isRefund
                    ? rechargePaymentService.handleRefundNotification(headers, body)
                    : rechargePaymentService.handleNotification(headers, body);
            // 支付返回 PROCESSED / DUPLICATE / REJECTED；退款返回 REVERSED / RELEASED / PENDING / DUPLICATE。
            // 一律回 SUCCESS 让微信停止重试（非终态已在 Inbox 与 refund_order 留痕，走补偿扫描）
            log.info("[微信{}通知] 处理完成: result={}, bodySize={}B", kind, result, body.length);
            return ResponseEntity.ok(SUCCESS_BODY);
        } catch (ServiceException e) {
            // 验签失败或订单/退款单状态冲突：HTTP 400 + FAIL，微信将按退避策略重试
            log.warn("[微信{}通知] 业务异常: code={}, msg={}, bodySize={}B",
                    kind, e.getCode(), e.getMessage(), body.length);
            return failResponse(HttpStatus.BAD_REQUEST,
                    e.getMessage() == null ? "业务异常" : e.getMessage());
        } catch (RuntimeException e) {
            // 系统内部错误：HTTP 500 + FAIL，微信将重试；Inbox 已留痕（领域方法内 markInbox FAILED）
            log.error("[微信{}通知] 系统异常: bodySize={}B", kind, body.length, e);
            return failResponse(HttpStatus.INTERNAL_SERVER_ERROR, "系统异常");
        }
    }

    /**
     * 提取微信 v3 通知必需的 4 个签名头。
     *
     * <p>缺任意一个则返回空 Map（{@link Collections#emptyMap()}），Controller 层拒绝。
     * 使用 {@link LinkedHashMap} 保证顺序稳定（便于测试断言 + 日志排查）。
     */
    private Map<String, String> extractWechatHeaders(HttpServletRequest request) {
        String timestamp = request.getHeader(HEADER_TIMESTAMP);
        String nonce = request.getHeader(HEADER_NONCE);
        String signature = request.getHeader(HEADER_SIGNATURE);
        String serial = request.getHeader(HEADER_SERIAL);
        if (StrUtil.hasBlank(timestamp, nonce, signature, serial)) {
            return Collections.emptyMap();
        }
        Map<String, String> headers = new LinkedHashMap<>(4);
        headers.put(HEADER_TIMESTAMP, timestamp);
        headers.put(HEADER_NONCE, nonce);
        headers.put(HEADER_SIGNATURE, signature);
        headers.put(HEADER_SERIAL, serial);
        return headers;
    }

    /** 构造微信要求的失败响应体：HTTP 4xx/5xx + {@code {"code":"FAIL","message":"..."}} */
    private ResponseEntity<Map<String, String>> failResponse(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("code", "FAIL", "message", message));
    }
}
