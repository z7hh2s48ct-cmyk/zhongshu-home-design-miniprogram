package cn.iocoder.yudao.framework.web.core.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SensitiveLogRedactor} 的单元测试（T13-04 安全加固，codex 六轮对抗审查后定型）。
 *
 * <p>验证：凭据字段值在 JSON 请求体中被<b>结构化</b>遮蔽（Jackson 解析，无正则绕过），敏感键不论值类型（含数值型）一律遮蔽，
 * 而形近键（barcode / promo-code）/ 非敏感字段不被误伤；非 JSON 或畸形 JSON（含缺左括号）整体省略而非回显原文；
 * {@link Map} 参数逐键（大小写不敏感）完整遮蔽且不修改入参；null 安全、不抛异常。
 *
 * <p>对抗性 fixture（对应 codex 三轮 A/B/C/D）：unicode 转义键 {@code {"co\u0064e":...}}、值内转义引号 {@code {"password":"a\"SECRET"}}、
 * 非 JSON 表单串整体省略、缺左括号畸形 JSON 整体省略、数值型凭据遮蔽、promo-code 形近键不误伤。
 *
 * <p>请求体解析异常识别（对应 codex 四/五/六轮）：{@link SensitiveLogRedactor#isBodyParseLeak} 识别 Spring
 * {@code HttpMessageNotReadableException} 与 Jackson {@code JsonProcessingException}，以 BFS 同时遍历 {@code cause}
 * 链与 {@code suppressed} 异常（identity 去重杜绝任意环，含自引用），深包装 / 被抑制的解析异常也能命中；遍历预算耗尽时
 * 保守返回 {@code true}（触发省略），供 {@code GlobalExceptionHandler} 与 {@code ApiAccessLogFilter} 脱敏异常文本。
 */
class SensitiveLogRedactorTest {

    @Test
    void redactMasksJsonCredentialValueAndKeepsOthers() {
        String body = "{\"code\":\"081abcDO-NOT-LEAK\",\"nickname\":\"张三\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("081abcDO-NOT-LEAK");
        assertThat(result).contains("\"code\":\"***\"");
        assertThat(result).contains("\"nickname\":\"张三\""); // 非敏感字段原样保留
    }

    @Test
    void redactMasksEverySensitiveJsonKey() {
        String body = "{\"password\":\"pv\",\"oldPassword\":\"opv\",\"newPassword\":\"npv\","
                + "\"confirmPassword\":\"cpv\",\"secret\":\"sv\",\"appSecret\":\"asv\",\"clientSecret\":\"csv\","
                + "\"token\":\"tv\",\"accessToken\":\"atv\",\"refreshToken\":\"rtv\",\"idToken\":\"itv\","
                + "\"session_key\":\"skv\",\"sessionKey\":\"skv2\",\"idCard\":\"icv\",\"bankCard\":\"bcv\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        // 所有敏感值均不得残留
        for (String leak : new String[]{"pv", "opv", "npv", "cpv", "sv", "asv", "csv",
                "tv", "atv", "rtv", "itv", "skv", "skv2", "icv", "bcv"}) {
            assertThat(result).doesNotContain("\"" + leak + "\"");
        }
        // 每个键仍在（结构不破坏），值被遮蔽
        assertThat(result)
                .contains("\"password\":\"***\"")
                .contains("\"session_key\":\"***\"")
                .contains("\"refreshToken\":\"***\"");
    }

    @Test
    void redactJsonBodyMasksNumericCredentialValue() {
        // 修 codex C：数值型凭据也必须遮蔽——Jackson 会把不带引号的数值 code/password 绑进 VO 的 String 字段而构成泄露，
        // 故敏感键不论值类型（数值/布尔/字符串/对象/数组）一律整体遮蔽；仅非敏感字段（msg）保留。
        String body = "{\"password\":12345678,\"code\":987654321,\"msg\":\"ok\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("12345678").doesNotContain("987654321");
        assertThat(result).contains("\"password\":\"***\"").contains("\"code\":\"***\"");
        assertThat(result).contains("\"msg\":\"ok\""); // 非敏感字段原样保留
    }

    @Test
    void redactDoesNotMaskLookalikeKeys() {
        // barcode / zipcode / encode 等形近键不应被 code 规则误伤（精确匹配键名，非子串）
        String body = "{\"barcode\":\"123\",\"zipcode\":\"456\",\"encode\":\"789\"}";

        assertThat(SensitiveLogRedactor.redactJsonBody(body)).isEqualTo(body);
    }

    @Test
    void redactJsonBodyOmitsNonJsonFormBodyInsteadOfRegex() {
        // 修 codex D：请求体不再用有缺陷的正则扫描——表单串（非 JSON，不以 { / [ 开头）无法结构化定位敏感字段，
        // 一律整体省略，含空格的值也随整体省略而不泄露（查询 / 表单凭据改走 redactMap）。
        String result = SensitiveLogRedactor.redactJsonBody("password=prefix SECRET-SUFFIX&user=tom");

        assertThat(result).isEqualTo("[omitted-unparseable]");
        assertThat(result).doesNotContain("SECRET-SUFFIX");
    }

    @Test
    void redactJsonBodyOmitsMissingLeftBraceJson() {
        // 修 codex B：缺左括号的畸形 JSON（不以 { / [ 开头）此前会落入表单正则分支而原样泄露，现一律整体省略。
        String result = SensitiveLogRedactor.redactJsonBody("\"code\":\"SECRET-DO-NOT-LEAK\"}");

        assertThat(result).isEqualTo("[omitted-unparseable]");
        assertThat(result).doesNotContain("SECRET-DO-NOT-LEAK");
    }

    @Test
    void redactJsonBodyDoesNotMaskPromoCodeLookalikeKey() {
        // 修 codex D：promo-code / discountCode 等形近键不应被 code 规则误伤（精确匹配键名，非子串 / 词边界）。
        String body = "{\"promo-code\":\"KEEP-ME\",\"discountCode\":\"KEEP-TOO\"}";

        assertThat(SensitiveLogRedactor.redactJsonBody(body)).isEqualTo(body);
    }

    @Test
    void redactIsCaseInsensitive() {
        assertThat(SensitiveLogRedactor.redactJsonBody("{\"CODE\":\"x\"}")).doesNotContain("\"x\"");
        assertThat(SensitiveLogRedactor.redactJsonBody("{\"Session_Key\":\"y\"}")).doesNotContain("\"y\"");
    }

    @Test
    void redactHandlesNullAndEmpty() {
        assertThat(SensitiveLogRedactor.redactJsonBody(null)).isNull();
        assertThat(SensitiveLogRedactor.redactJsonBody("")).isEmpty();
    }

    @Test
    void redactLeavesPlainTextWithoutSensitiveKeysUnchanged() {
        String body = "{\"pageNo\":1,\"pageSize\":10,\"keyword\":\"sofa\"}";

        assertThat(SensitiveLogRedactor.redactJsonBody(body)).isEqualTo(body);
    }

    @Test
    void redactMasksUnicodeEscapedSensitiveKey() {
        // #2：unicode 转义键 co\u0064e 实为 code——正则漏遮，Jackson 解析已解码键名，故被识别并遮蔽
        String body = "{\"co\\u0064e\":\"REAL-CODE-DO-NOT-LEAK\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("REAL-CODE-DO-NOT-LEAK");
        assertThat(result).contains("\"code\":\"***\""); // 键已解码为 code 并遮蔽其值
    }

    @Test
    void redactMasksValueContainingEscapedQuoteCompletely() {
        // #2：值内转义引号 {"password":"prefix\"SECRET-SUFFIX"}——首轮正则被内层引号截断泄露 SECRET-SUFFIX，
        // Jackson 解析出完整字符串值后整体遮蔽，无残留
        String body = "{\"password\":\"prefix\\\"SECRET-SUFFIX\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("SECRET-SUFFIX").doesNotContain("prefix");
        assertThat(result).contains("\"password\":\"***\"");
    }

    @Test
    void redactOmitsMalformedJsonInsteadOfEchoingSecret() {
        // #2：截断的畸形 JSON（形似 JSON 以 { 开头但无法解析）——整体省略，绝不回显可能含凭据的原文
        String malformed = "{\"code\":\"SECRET-DO-NOT-LEAK\""; // 缺少右括号

        String result = SensitiveLogRedactor.redactJsonBody(malformed);

        assertThat(result).doesNotContain("SECRET-DO-NOT-LEAK");
        assertThat(result).isEqualTo("[omitted-unparseable]");
    }

    @Test
    void redactMasksEntireNestedValueUnderSensitiveKey() {
        // 敏感键的值为对象/数组时整体遮蔽（不逐层展开，杜绝嵌套结构内的凭据残留）
        String body = "{\"token\":{\"access\":\"SECRET-INNER\",\"n\":1},\"keep\":\"v\"}";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("SECRET-INNER");
        assertThat(result).contains("\"token\":\"***\"");
        assertThat(result).contains("\"keep\":\"v\"");
    }

    @Test
    void redactMasksSensitiveValuesInsideArrays() {
        String body = "[{\"code\":\"A-DO-NOT-LEAK\"},{\"code\":\"B-DO-NOT-LEAK\"}]";

        String result = SensitiveLogRedactor.redactJsonBody(body);

        assertThat(result).doesNotContain("A-DO-NOT-LEAK").doesNotContain("B-DO-NOT-LEAK");
        assertThat(result).contains("\"code\":\"***\"");
    }

    @Test
    void redactMapMasksFullValueWithSpacesAndNeverMutatesInput() {
        // #3：结构化遮蔽参数 Map——值含空格也完整遮蔽；返回新 Map，绝不修改入参（调用方 Map 可能仍被业务使用）
        Map<String, String> params = new LinkedHashMap<>();
        params.put("password", "prefix SECRET-SUFFIX");
        params.put("code", "LOGIN-CODE-DO-NOT-LEAK");
        params.put("nickname", "tom");

        Map<String, String> safe = SensitiveLogRedactor.redactMap(params);

        assertThat(safe.get("password")).isEqualTo("***");
        assertThat(safe.get("code")).isEqualTo("***");
        assertThat(safe.get("nickname")).isEqualTo("tom");
        assertThat(safe.toString()).doesNotContain("SECRET-SUFFIX").doesNotContain("LOGIN-CODE-DO-NOT-LEAK");
        // 入参未被修改
        assertThat(params.get("password")).isEqualTo("prefix SECRET-SUFFIX");
        assertThat(params.get("code")).isEqualTo("LOGIN-CODE-DO-NOT-LEAK");
    }

    @Test
    void redactMapHandlesNullAndEmpty() {
        assertThat(SensitiveLogRedactor.redactMap(null)).isEmpty();
        assertThat(SensitiveLogRedactor.redactMap(new LinkedHashMap<>())).isEmpty();
    }

    @Test
    void isSensitiveKeyMatchesExactNotSubstring() {
        assertThat(SensitiveLogRedactor.isSensitiveKey("code")).isTrue();
        assertThat(SensitiveLogRedactor.isSensitiveKey("CODE")).isTrue();
        assertThat(SensitiveLogRedactor.isSensitiveKey("session_key")).isTrue();
        assertThat(SensitiveLogRedactor.isSensitiveKey("barcode")).isFalse();
        assertThat(SensitiveLogRedactor.isSensitiveKey("zipcode")).isFalse();
        assertThat(SensitiveLogRedactor.isSensitiveKey("promo-code")).isFalse(); // 修 codex D：连字符形近键不误伤
        assertThat(SensitiveLogRedactor.isSensitiveKey("discountCode")).isFalse();
        assertThat(SensitiveLogRedactor.isSensitiveKey(null)).isFalse();
    }

    @Test
    void parseTreeQuietlyReturnsNullOnMalformedWithoutThrowing() {
        // 供 ApiAccessLogFilter 复用：畸形输入返回 null（不抛、不 log 原文），由调用方走省略分支
        assertThat(SensitiveLogRedactor.parseTreeQuietly("{\"code\":")).isNull();
        assertThat(SensitiveLogRedactor.parseTreeQuietly("")).isNull();
        assertThat(SensitiveLogRedactor.parseTreeQuietly(null)).isNull();
        assertThat(SensitiveLogRedactor.parseTreeQuietly("{\"code\":\"x\"}")).isNotNull();
    }

    @Test
    void isBodyParseLeakDetectsSpringAndJacksonParseExceptions() {
        // Spring 请求体不可读异常（非弃用的双参构造）
        HttpMessageNotReadableException springEx = new HttpMessageNotReadableException(
                "JSON parse error", new MockHttpInputMessage(new byte[0]));
        assertThat(SensitiveLogRedactor.isBodyParseLeak(springEx)).isTrue();

        // 真实 Jackson 解析异常：畸形 JSON（未加引号的 token）→ JsonParseException（JsonProcessingException 子类），
        // 其 message 会回显原始 token（正是 codex 复现的泄露源）
        JsonProcessingException jacksonEx;
        try {
            new ObjectMapper().readTree("{\"code\":LOGINSECRET}");
            throw new IllegalStateException("expected parse failure");
        } catch (JsonProcessingException e) {
            jacksonEx = e;
        }
        assertThat(jacksonEx.getMessage()).contains("LOGINSECRET"); // fixture 确实含凭据
        assertThat(SensitiveLogRedactor.isBodyParseLeak(jacksonEx)).isTrue();

        // 嵌套 cause 链：RuntimeException -> HttpMessageNotReadableException
        assertThat(SensitiveLogRedactor.isBodyParseLeak(new RuntimeException("wrap", springEx))).isTrue();
        // 多层嵌套：RuntimeException -> RuntimeException -> JsonProcessingException
        assertThat(SensitiveLogRedactor.isBodyParseLeak(
                new RuntimeException("a", new RuntimeException("b", jacksonEx)))).isTrue();
    }

    @Test
    void isBodyParseLeakReturnsFalseForUnrelatedAndNull() {
        assertThat(SensitiveLogRedactor.isBodyParseLeak(null)).isFalse();
        assertThat(SensitiveLogRedactor.isBodyParseLeak(new RuntimeException("boom"))).isFalse();
        assertThat(SensitiveLogRedactor.isBodyParseLeak(new IllegalStateException("bad state"))).isFalse();
        // 深层 cause 链中无 body-parse 异常
        assertThat(SensitiveLogRedactor.isBodyParseLeak(
                new RuntimeException("a", new RuntimeException("b", new Exception("c"))))).isFalse();
    }

    @Test
    void isBodyParseLeakGuardsAgainstSelfReferencingCause() {
        // 自引用 cause 链：验证 BFS 遍历不会死循环（identity 去重，重复节点直接跳过）
        Throwable selfRef = new Throwable("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertThat(SensitiveLogRedactor.isBodyParseLeak(selfRef)).isFalse();
    }

    @Test
    void isBodyParseLeakDetectsBodyParseBeneathDeepWrapperChain() {
        // codex 五轮 P2：解析异常深埋于 >32 层包装下（原实现深度上限 32 会漏判），BFS 无深度上限（仅受预算约束）应命中
        JsonProcessingException jacksonEx = realJacksonParseException();
        Throwable deep = jacksonEx;
        for (int i = 0; i < 40; i++) {
            deep = new RuntimeException("wrap-" + i, deep);
        }
        assertThat(SensitiveLogRedactor.isBodyParseLeak(deep)).isTrue();

        // 对照：同样 40 层但叶子非解析异常（且在预算内），应正确返回 false，证明预算未被过早触发
        Throwable deepPlain = new Exception("leaf");
        for (int i = 0; i < 40; i++) {
            deepPlain = new RuntimeException("wrap-" + i, deepPlain);
        }
        assertThat(SensitiveLogRedactor.isBodyParseLeak(deepPlain)).isFalse();
    }

    @Test
    void isBodyParseLeakDetectsSuppressedBodyParseException() {
        // codex 五轮 P2：解析异常作为 suppressed（而非 cause）挂载——原实现从不检视 suppressed 会漏判
        HttpMessageNotReadableException springEx = new HttpMessageNotReadableException(
                "JSON parse error", new MockHttpInputMessage(new byte[0]));
        Throwable outer = new RuntimeException("outer");
        outer.addSuppressed(springEx);
        assertThat(SensitiveLogRedactor.isBodyParseLeak(outer)).isTrue();

        // 嵌套 suppressed：suppressed 的 cause 链深处藏解析异常
        Throwable outer2 = new RuntimeException("outer2");
        outer2.addSuppressed(new RuntimeException("sup", realJacksonParseException()));
        assertThat(SensitiveLogRedactor.isBodyParseLeak(outer2)).isTrue();

        // 对照：suppressed 中无解析异常
        Throwable outer3 = new RuntimeException("outer3");
        outer3.addSuppressed(new RuntimeException("sup-plain"));
        assertThat(SensitiveLogRedactor.isBodyParseLeak(outer3)).isFalse();
    }

    @Test
    void isBodyParseLeakReturnsTrueWhenTraversalBudgetExhausted() {
        // codex 五轮 P2：遍历预算耗尽（>128 个「不同节点」且均非解析异常）时保守返回 true（触发省略），
        // 宁可少记日志也绝不让未经完整检视的异常链绕过脱敏
        Throwable huge = new Exception("leaf");
        for (int i = 0; i < 300; i++) {
            huge = new RuntimeException("wrap-" + i, huge);
        }
        assertThat(SensitiveLogRedactor.isBodyParseLeak(huge)).isTrue();
    }

    @Test
    void isBodyParseLeakIgnoresRepeatedReferencesWhenChargingBudget() {
        // codex 六轮 P2：同一「普通」异常被反复 addSuppressed（远超预算 128 次），实际只有 2 个不同节点。
        // 去重前移到入队后，预算只对不同节点计费，故不应误判预算耗尽返回 true（避免过度脱敏本可正常排障的合法日志）。
        Throwable repeated = new RuntimeException("sup-plain");
        Throwable outer = new RuntimeException("outer");
        for (int i = 0; i < 200; i++) {
            outer.addSuppressed(repeated);
        }
        assertThat(SensitiveLogRedactor.isBodyParseLeak(outer)).isFalse();

        // 对照：重复引用中若真藏解析异常，仍应命中——去重只影响预算计费，不影响检出能力
        Throwable outer2 = new RuntimeException("outer2");
        for (int i = 0; i < 200; i++) {
            outer2.addSuppressed(repeated);
        }
        outer2.addSuppressed(realJacksonParseException());
        assertThat(SensitiveLogRedactor.isBodyParseLeak(outer2)).isTrue();
    }

    /** 用真实 Jackson 解析畸形 body（未加引号 token）得到 {@link JsonProcessingException}，其 message 回显原始 token。 */
    private static JsonProcessingException realJacksonParseException() {
        try {
            new ObjectMapper().readTree("{\"code\":LOGINSECRET}");
            throw new IllegalStateException("expected parse failure");
        } catch (JsonProcessingException e) {
            return e;
        }
    }
}
