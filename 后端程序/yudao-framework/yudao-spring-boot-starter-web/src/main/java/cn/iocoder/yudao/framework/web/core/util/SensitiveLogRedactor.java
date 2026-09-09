package cn.iocoder.yudao.framework.web.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.converter.HttpMessageNotReadableException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 敏感日志脱敏工具（T13-04 安全加固，codex 七轮对抗审查后定型）。
 *
 * <p>用途：在把 HTTP 请求体 / 查询参数写入<b>日志或错误日志库、访问日志库之前</b>，遮蔽其中的凭据类字段值
 * （一次性登录 {@code code}、密码、密钥、令牌、{@code session_key} 等），避免敏感凭据经
 * {@code ApiAccessLogInterceptor}（非 prod 控制台日志）、{@code GlobalExceptionHandler}（全环境错误日志库的
 * {@code requestParams}）与 {@code ApiAccessLogFilter}（访问日志库）落盘泄露。
 *
 * <p><b>仅用于日志输出，绝不用于真实业务处理</b>：交给反序列化的原始请求体不受影响，脱敏只降低日志可读性、不改变业务语义。
 *
 * <p><b>为何不用正则直接扫字符串</b>（首轮实现被 codex 证伪）：正则会漏遮 unicode 转义键（把 code 中的字母写成 unicode
 * 转义，解析后仍是 code，正则按字面匹配不到）、被值内转义引号截断（password 值里藏一个转义引号再接 SECRET，正则会在引号处
 * 截断而泄露 SECRET）、把 promo-code 等形近键过度遮蔽、还会把逗号误当分隔符。故本类改用<b>专用 JSON 解析器结构化遮蔽</b>——
 * <ul>
 *   <li>JSON 请求体（{@link #redactJsonBody}）：解析为树，递归把敏感键的值<b>不论类型</b>（字符串 / 数值 / 布尔 / 对象 / 数组）
 *       整体替换为 {@code ***}（Jackson 已解码键的 unicode 转义、已解析值的转义引号，故无绕过；数值型凭据如 password 的值是
 *       不带引号的 12345678 亦被遮蔽）；<b>非 JSON 或畸形 JSON 一律整体省略</b>为 {@code [omitted-unparseable]}
 *       （绝不回显可能含凭据的原文，也杜绝「缺左括号的畸形 JSON」绕过）；</li>
 *   <li>{@link Map} 参数（{@code getParamMap}，含查询串 / 表单键值，键已由容器 URL 解码）：{@link #redactMap} 返回<b>新 Map</b>、
 *       逐键（大小写不敏感）完整遮蔽，绝不修改入参——表单 / 查询凭据统一走此路径，不再用有缺陷的正则扫原文。</li>
 *   <li>请求体解析异常（{@link #isBodyParseLeak}）：畸形 JSON / 类型不匹配触发的 {@code HttpMessageNotReadableException} /
 *       Jackson {@code JsonProcessingException}，其 {@code message} / {@code cause} / 渲染的 {@code stackTrace} 会回显原始请求体 token，
 *       由 {@code GlobalExceptionHandler} 据此把错误日志库的异常文本整体替换为 {@link #BODY_PARSE_OMITTED}（codex 四轮修正）。</li>
 * </ul>
 *
 * <p><b>敏感键不论值类型一律遮蔽</b>（codex C 修正）：数值型凭据（如 password 的值是不带引号的整数）也会被 Jackson 绑进 VO 的
 * String 字段而构成泄露，故 {@link #redactJsonBody} 对敏感键的数值 / 布尔 / 对象 / 数组值同样整体遮蔽；响应态整数状态码由
 * {@code ApiAccessLogFilter} 的 {@code CommonResult} 分支（只处理 {@code data}、跳过 {@code code}/{@code msg}）单独保障，不经此路径。
 *
 * <p><b>专用 {@link ObjectMapper} 从不记录输入</b>（区别于 {@code JsonUtils.parseTree}——后者解析失败会 {@code log.error}
 * 原文，反而泄露）；解析 / 序列化失败一律走「省略」分支，绝不把原文带出。
 *
 * @author 众墅之家设计平台（T13-04）
 */
public final class SensitiveLogRedactor {

    /**
     * 需遮蔽的凭据 / 高敏字段名（规范化大小写形态）。刻意只覆盖凭据与高敏 PII，不含普通业务字段，
     * 以在「安全」与「日志可调试性」之间取得平衡。作为<b>单一事实源</b>，其小写集合驱动 {@link #isSensitiveKey}，
     * 供本类与 {@code ApiAccessLogFilter} 共用（各处不再各定一份键数组而遗漏）。
     */
    private static final String[] SENSITIVE_KEYS = {
            "code", "password", "oldPassword", "newPassword", "confirmPassword", "pwd",
            "secret", "appSecret", "clientSecret", "token", "accessToken", "refreshToken",
            "idToken", "session_key", "sessionKey", "idCard", "bankCard"};

    /** 敏感键的小写集合，用于大小写不敏感的精确匹配（{@code barcode} / {@code zipcode} / {@code promo-code} 等形近键不误伤）。 */
    private static final Set<String> SENSITIVE_LOWER = Stream.of(SENSITIVE_KEYS)
            .map(k -> k.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());

    /** 遮蔽占位符。 */
    private static final String MASK = "***";

    /** 畸形 / 非 JSON / 无法安全解析的输入的整体省略占位符（绝不回显原文）。 */
    private static final String OMITTED = "[omitted-unparseable]";

    /**
     * 请求体解析异常的脱敏占位符（codex 四轮修正）：写入错误日志库的 {@code exceptionMessage} /
     * {@code exceptionRootCauseMessage} / {@code exceptionStackTrace} 字段，替代会回显原始请求体 token
     * （如畸形 JSON 的 {@code Unrecognized token 'XXX'}）的原文，杜绝一次性登录 {@code code} / 密码等凭据经异常文本落库泄露。
     */
    public static final String BODY_PARSE_OMITTED = "[omitted-body-parse]";

    /** 专用 {@link ObjectMapper}：脱敏解析 / 序列化，<b>从不记录输入</b>。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * {@link #isBodyParseLeak} 允许发现的「不同节点」上限（codex 七轮）：逾此上限即保守视为潜在泄露返回 {@code true}。
     * 在<b>入队前</b>以 {@code visited.size()} 判定，杜绝单个异常挂载海量不同 {@code suppressed} 时队列与 visited 集合被一次性撑大。
     */
    private static final int MAX_DISTINCT_NODES = 128;

    private SensitiveLogRedactor() {
    }

    /**
     * 判断键名是否为敏感凭据键（大小写不敏感、精确匹配，非子串）。
     *
     * @param key 字段名，可为 {@code null}
     * @return 命中敏感键集返回 {@code true}
     */
    public static boolean isSensitiveKey(String key) {
        return key != null && SENSITIVE_LOWER.contains(key.toLowerCase(Locale.ROOT));
    }

    /**
     * 判断异常链是否为「请求体解析异常」（codex 四轮修正）——此类异常的 {@code message} / {@code cause} /
     * 渲染出的 {@code stackTrace} 文本会回显原始请求体 token（如畸形 JSON 的 {@code Unrecognized token 'XXX'}、
     * 类型不匹配的原始值），可能含一次性登录 {@code code} / 密码等凭据，落库 / 记日志前必须整体脱敏。
     *
     * <p>覆盖 Spring 的 {@link HttpMessageNotReadableException} 与 Jackson 的 {@link JsonProcessingException}
     * （含 {@code JsonParseException}、{@code InvalidFormatException}、{@code MismatchedInputException} 等全部子类），
     * 以 BFS 同时遍历 {@code cause} 链与 {@code suppressed} 异常（入队即 identity 去重，杜绝任意环含自引用、重复引用不占上限）；
     * 发现的不同节点数逾 {@link #MAX_DISTINCT_NODES} 仍未能完整检视时<b>保守返回 {@code true}</b>（触发省略、并有界化辅助集合），宁可少记日志也绝不泄露凭据。
     *
     * @param e 异常，可为 {@code null}
     * @return 异常链中任一层（含 cause / suppressed）为请求体解析异常，或发现的不同节点逾上限时返回 {@code true}
     */
    public static boolean isBodyParseLeak(Throwable e) {
        if (e == null) {
            return false;
        }
        // codex 五轮（修 P2）：改用 BFS 同时遍历 cause 链与 suppressed 异常——原实现只走 cause、深度上限 32 且不查 suppressed，
        // 深包装 / 被抑制的解析异常会漏判，其 token 仍会出现在渲染的 stackTrace 里。identity 去重杜绝任意环（含自引用）。
        // codex 六轮（修 P2）：identity 去重从「出队时」前移到「入队时」，使节点上限只对「不同节点」计费——原实现在去重前扣预算，
        // 同一普通异常被 addSuppressed 逾上限次即误判耗尽返回 true，过度脱敏本可正常排障的合法日志。
        // codex 七轮（修 P2）：节点上限改为在「入队前」以 visited.size() 判定并即时返回，杜绝单个异常挂载海量不同 suppressed 时
        // 队列与 visited 集合在下次检查前被一次性撑大（无谓内存 / GC 压力）；可观察的 true/false 语义与六轮完全一致（发现的不同节点逾上限即保守省略）。
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> queue = new ArrayDeque<>();
        visited.add(e);
        queue.add(e);
        while (!queue.isEmpty()) {
            Throwable cur = queue.poll();
            if (cur instanceof HttpMessageNotReadableException
                    || cur instanceof JsonProcessingException) {
                return true;
            }
            Throwable cause = cur.getCause();
            if (cause != null && visited.add(cause)) { // 入队即去重：杜绝任意环，重复引用不再入队、不占上限
                if (visited.size() > MAX_DISTINCT_NODES) {
                    return true; // 发现的不同节点已逾上限：保守视为潜在泄露，同时有界化辅助集合
                }
                queue.add(cause);
            }
            for (Throwable suppressed : cur.getSuppressed()) {
                if (visited.add(suppressed)) {
                    if (visited.size() > MAX_DISTINCT_NODES) {
                        return true;
                    }
                    queue.add(suppressed);
                }
            }
        }
        return false;
    }

    /**
     * 遮蔽 JSON 请求体中的敏感凭据字段值，供日志 / 错误日志库 / 访问日志库安全输出。
     *
     * <p><b>严格 JSON 语义</b>：仅当去空白后以 <code>{</code> 或 <code>[</code> 开头且能被 Jackson 解析时，才结构化遮蔽其中敏感键的值；
     * 其余一切输入（缺左括号的畸形 JSON、表单串、纯文本等）无法安全定位敏感字段，<b>一律整体省略</b>为
     * {@code [omitted-unparseable]}，绝不回显可能含凭据的原文。查询串 / 表单键值请改走 {@link #redactMap}（键已由容器解码）。
     *
     * @param body 原始请求体文本，可为 {@code null}
     * @return 遮蔽 / 省略后的文本；入参为 {@code null} 或空串时原样返回
     */
    public static String redactJsonBody(String body) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        String trimmed = body.trim();
        // 修 codex B：不以 { / [ 开头（如缺左括号的畸形 JSON "code":"SECRET"}、表单串）无法结构化定位敏感字段，
        // 一律整体省略，绝不落入任何按原文扫描的分支而泄露凭据。
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) {
            return OMITTED;
        }
        JsonNode root = parseTreeQuietly(trimmed);
        if (root == null) {
            // 形似 JSON 却无法解析：无法定位敏感字段，整体省略——绝不回显可能含凭据片段的原文。
            return OMITTED;
        }
        maskNode(root);
        return writeQuietly(root);
    }

    /**
     * 结构化遮蔽参数 Map（如 {@code ServletUtils.getParamMap}，含查询串 / 表单键值，键已由容器 URL 解码）：
     * 返回<b>新 Map</b>，敏感键（大小写不敏感）的值整体替换为 {@code ***}。
     *
     * <p>绝不修改入参（调用方的 Map 可能仍被业务使用）；完整遮蔽值，不受空格 / 特殊字符 / URL 编码影响。
     *
     * @param params 原始参数 Map，可为 {@code null}
     * @return 遮蔽后的新 Map；入参为 {@code null} 时返回空 Map
     */
    public static Map<String, String> redactMap(Map<String, String> params) {
        if (params == null) {
            return Collections.emptyMap();
        }
        Map<String, String> safe = new LinkedHashMap<>(Math.max(16, params.size()));
        for (Map.Entry<String, String> entry : params.entrySet()) {
            safe.put(entry.getKey(), isSensitiveKey(entry.getKey()) ? MASK : entry.getValue());
        }
        return safe;
    }

    /**
     * 安静解析 JSON 为树：<b>解析失败返回 {@code null}，绝不记录输入</b>（区别于 {@code JsonUtils.parseTree} 会 {@code log.error} 原文）。
     *
     * <p>供本类与 {@code ApiAccessLogFilter} 复用，确保「畸形请求体不会因解析失败而被 log 原文」。
     *
     * @param json JSON 文本，可为 {@code null} / 空
     * @return 解析出的 {@link JsonNode}；输入为空或畸形时返回 {@code null}
     */
    public static JsonNode parseTreeQuietly(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            // 绝不 log 输入（可能含凭据）；由调用方决定省略策略。
            return null;
        }
    }

    /** 递归遮蔽敏感键的值：不论值类型（字符串 / 数值 / 布尔 / 对象 / 数组）整体替换为 {@code ***}；仅 null 值保留（无内容可泄露）。 */
    private static void maskNode(JsonNode node) {
        if (node instanceof ObjectNode obj) {
            List<String> toMask = new ArrayList<>();
            List<JsonNode> toRecurse = new ArrayList<>();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                JsonNode value = entry.getValue();
                if (isSensitiveKey(entry.getKey())) {
                    // 修 codex C：敏感键不论值类型一律整体遮蔽（含转义引号、unicode、嵌套结构、数值型凭据，无绕过）——
                    // 数值型 code/password（如 {"password":12345678}）也会被 Jackson 绑进 VO 的 String 字段，故不能只遮字符串；
                    // 仅 null 值无内容可泄露，保留以维持「字段存在但为空」的语义。
                    if (!value.isNull()) {
                        toMask.add(entry.getKey());
                    }
                } else {
                    toRecurse.add(value);
                }
            }
            // 先收集再修改，避免遍历期结构性修改；put 覆盖既有键不改变键集，安全。
            for (String key : toMask) {
                obj.put(key, MASK);
            }
            for (JsonNode child : toRecurse) {
                maskNode(child);
            }
        } else if (node instanceof ArrayNode arr) {
            for (JsonNode child : arr) {
                maskNode(child);
            }
        }
    }

    /** 安静序列化：失败也绝不回显原文，返回省略占位符。 */
    private static String writeQuietly(JsonNode root) {
        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            return OMITTED;
        }
    }
}
