package cn.iocoder.yudao.server.wiring;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 敏感传输层日志级别守卫的契约测试（B2 T13-10/11，铁律 1）。
 *
 * <p>覆盖四类判定，缺一即漏：
 * <ol>
 *   <li><b>必须拦</b>——敏感 logger 自身或后代被放开至 DEBUG/TRACE（含大小写变体、松散绑定的环境变量形式），
 *       以及经 {@code logging.group.*} 日志组展开后命中敏感 logger 的成员（codex round-7 P1①）；</li>
 *   <li><b>必须放行</b>——{@code logging.level.root=DEBUG}（用户约束：运维仍可开 root 排查业务）、
 *       敏感 logger 的<b>祖先</b>（XML 已钉后代，祖先设置不改变其有效级别，拦它纯属误报）、
 *       「前缀相同但非后代」的兄弟 logger（鉴别 {@code startsWith} 漏写 {@code + "."} 的实现缺陷）、
 *       以及成员均为普通业务 logger 或祖先的日志组；</li>
 *   <li><b>违例消息</b>——只回显 logger 名与级别，绝不回显任何密钥/签名样式内容（铁律 1）；</li>
 *   <li><b>绑定失败净化</b>——{@code logging.level.*} 被误配为密钥/签名 URL 等非法级别值时，抛无 cause、不含原始值的
 *       安全异常并 fail-closed（codex round-7 P1②，铁律 1）。</li>
 * </ol>
 *
 * <p>本测试<b>完全不依赖 Logback 初始化状态</b>：被测对象读的是 {@link org.springframework.core.env.Environment}
 * 属性而非 logger 的有效级别，这正是 {@link SensitiveLoggingPolicy} 类 javadoc 中记录的启动时序实证结论
 * （EnvironmentPostProcessor 早于 LoggingApplicationListener 执行）。
 */
class SensitiveLoggingPolicyTest {

    /** 铁律 1 金丝雀：一旦出现在异常消息中即为泄露，断言必须为红。 */
    private static final String SECRET_ID_CANARY = "AKIDCANARY1a2b3c4d5e6f7a8b";
    private static final String SECRET_KEY_CANARY = "SECRETKEYCANARY9z8y7x6w5v4u3t2s";
    private static final String SIGNATURE_CANARY = "X-Amz-Signature=SIGCANARY0f1e2d3c4b5a6978";

    /** 普通属性源：走 {@code DefaultPropertyMapper}，键名按原样（含点号）绑定。 */
    private StandardEnvironment envWith(Map<String, Object> props) {
        StandardEnvironment env = new StandardEnvironment();
        // 移除宿主的 systemProperties / systemEnvironment，令测试不受 CI 或本机
        // LOGGING_LEVEL_* / SPRING_PROFILES_ACTIVE 等既有变量影响（承 T13-01 测试同一纪律）。
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("t1310-logging", props));
        return env;
    }

    /**
     * 环境变量属性源。两个条件<b>必须同时满足</b>，否则测试会「假失败」并误导为「环境变量绕过路径不存在」：
     * <ol>
     *   <li>类型是 {@link SystemEnvironmentPropertySource}；</li>
     *   <li><b>名字必须是 {@code systemEnvironment}</b>（或以 {@code -systemEnvironment} 结尾）——实证（javap
     *       {@code SpringConfigurationPropertySource.isSystemEnvironmentPropertySource} 字节码：offset 6
     *       {@code instanceof SystemEnvironmentPropertySource} 类型检查 ＋ offset 12 {@code ldc "systemEnvironment"}
     *       名字检查，二者<b>同时成立</b>才选 {@code SYSTEM_ENVIRONMENT_MAPPERS}，否则 {@code DEFAULT_MAPPERS}）表明
     *       Boot 是<b>类型与名字共同决定</b> mapper 的，故上述两条件缺一不可。</li>
     * </ol>
     * 若名字不符（如 {@code "t1310-env"}），即便类型正确也会走 {@code DefaultPropertyMapper}，把
     * {@code LOGGING_LEVEL_A_B} 解析成畸形单元素 {@code "logginglevelab"}（下划线被当作非法字符丢弃），
     * Binder 绑不到任何 {@code logging.level.*}。故此处用 {@code replace} 原位替换宿主 source，保留其名字，
     * 同时把真实进程环境变量隔离在外（不受 CI／本机 {@code LOGGING_LEVEL_*} 影响）。
     *
     * <p>对照实验已证实真实进程环境变量可被正确绑定（同一 Binder 调用得到
     * {@code {root=WARN, org.apache.hc.client5.http.wire=DEBUG}}），即绕过路径真实存在、守卫拦得住。
     */
    private StandardEnvironment envWithEnvVars(Map<String, Object> vars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, vars));
        return env;
    }

    private Map<String, Object> levels(String... keyValuePairs) {
        Map<String, Object> props = new HashMap<>();
        for (int i = 0; i < keyValuePairs.length; i += 2) {
            props.put("logging.level." + keyValuePairs[i], keyValuePairs[i + 1]);
        }
        return props;
    }

    // ---------------------------------------------------------------- 必须拦

    @Test
    void apacheTransportLoggerItselfSetToDebugFails() {
        // 自身：Boot 会对它直接 setLogLevel(DEBUG)，覆盖 XML 的 INFO 钉级别
        // → DefaultManagedHttpClientConnection.onRequestSubmitted() 的 isDebugEnabled() 门控打开，
        //   逐个 debug("{} >> {}", id, header) 打印含 Authorization: AWS4-HMAC-SHA256 Credential=<secret-id> 的请求头
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("org.apache.hc.client5.http", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logging.level.org.apache.hc.client5.http")
                .hasMessageContaining("DEBUG")
                .hasMessageContaining("铁律 1");
    }

    @Test
    void apacheHeadersChildLoggerSetToDebugFails() {
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("org.apache.hc.client5.http.headers", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.headers");
    }

    @Test
    void apacheWireChildLoggerSetToTraceFails() {
        // 后代 + TRACE：wire 日志输出完整线路内容（含请求/响应 body），泄露面比 headers 更大
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("org.apache.hc.client5.http.wire", "TRACE"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire")
                .hasMessageContaining("TRACE");
    }

    @Test
    void awsSdkRootLoggerSetToDebugFails() {
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("software.amazon.awssdk", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("software.amazon.awssdk");
    }

    @Test
    void awsSdkResponseInputStreamDescendantSetToDebugFails() {
        // abort 路径的原始异常可能内含预签名 URL，经 debug(Supplier, Throwable) 落盘
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(
                envWith(levels("software.amazon.awssdk.core.ResponseInputStream", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ResponseInputStream");
    }

    @Test
    void deepDescendantOfSensitiveLoggerFails() {
        // 多级后代同样必须拦：Boot 对任意 logger 名都会 setLogLevel，深度不限
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(
                levels("org.apache.hc.client5.http.impl.io.DefaultManagedHttpClientConnection", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DefaultManagedHttpClientConnection");
    }

    @Test
    void lowercaseLevelValueStillFails() {
        // Boot 的 LogLevel 绑定宽松（debug → LogLevel.DEBUG）；守卫若改按字符串比较将漏掉此形式
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("org.apache.hc.client5.http.wire", "debug"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void uppercaseLoggerNameVariantFails() {
        // 纵深防御：不依赖 Spring 内部 ConfigurationPropertyName 的小写化实现细节，
        // 显式归一使大写变体（本身即异常配置）无法成为绕过路径
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels("ORG.APACHE.HC.CLIENT5.HTTP", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void relaxedBindingEnvironmentVariableFormFails() {
        // 真实生产绕过路径：容器编排里注入一个环境变量即可，无需改代码或 XML
        Map<String, Object> vars = new HashMap<>();
        vars.put("LOGGING_LEVEL_ORG_APACHE_HC_CLIENT5_HTTP_WIRE", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWithEnvVars(vars)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void relaxedBindingEnvVarForAwsSdkFails() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("LOGGING_LEVEL_SOFTWARE_AMAZON_AWSSDK", "TRACE");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWithEnvVars(vars)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("software.amazon.awssdk");
    }

    @Test
    void loggingGroupExpandingToSensitiveLoggerFails() {
        // codex round-7 P1①：Boot 对 logging.level.<组名> 命中组时展开成员逐个 setLogLevel（configureLogLevel 字节码实证）。
        // 只查 logging.level 键（组名 cos 非敏感）会漏；守卫镜像 Boot 展开成员后判定 → 必须拦下敏感成员。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos", "org.apache.hc.client5.http.headers,org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logging.level.cos")
                .hasMessageContaining("org.apache.hc.client5.http.wire")
                .hasMessageContaining("铁律 1");
    }

    @Test
    void loggingGroupExpandingToSensitiveLoggerReportsEachMemberOnce() {
        // 组含两个敏感成员 → 聚合两条违例（各占一行「 - 」），证明逐个成员判定、未提前截断
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos", "org.apache.hc.client5.http.headers,org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "TRACE");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> {
                    String message = thrown.getMessage();
                    assertThat(message)
                            .contains("org.apache.hc.client5.http.headers")
                            .contains("org.apache.hc.client5.http.wire");
                    assertThat(message.split("\n - ", -1)).hasSize(3); // 表头 + 2 条成员违例
                });
    }

    @Test
    void relaxedBindingEnvironmentVariableGroupFormFails() {
        // 松散绑定的环境变量组形式同样展开：LOGGING_GROUP_COS 定义组、LOGGING_LEVEL_COS 放开为 DEBUG
        Map<String, Object> vars = new HashMap<>();
        vars.put("LOGGING_GROUP_COS", "org.apache.hc.client5.http.wire");
        vars.put("LOGGING_LEVEL_COS", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWithEnvVars(vars)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    // -------------------------------------------------------------- 必须放行

    @Test
    void rootLoggerDebugPasses() {
        // 用户明确约束：只查敏感 logger、不查 root。XML 已钉住 5 个敏感 logger，
        // root=DEBUG 不覆盖它们，运维仍可开 root 排查业务问题——拦它将牺牲正常诊断能力
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(levels("root", "DEBUG"))))
                .doesNotThrowAnyException();
    }

    @Test
    void ancestorLoggerDebugPasses() {
        // 祖先不拦：Logback 有效级别取最近的已声明级别祖先，XML 已显式声明这 5 个 logger 为 INFO，
        // handleParentLevelChange() 遇自身非 null 的 level 即停止继承更新 → org.apache=DEBUG 实际不泄露。
        // 拦祖先纯属误报，且会阻碍运维排查其它 Apache 组件
        Map<String, Object> props = levels("org.apache", "DEBUG");
        props.put("logging.level.software.amazon", "DEBUG");
        props.put("logging.level.org", "TRACE");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void siblingLoggerSharingPrefixPasses() {
        // 鉴别力用例：若实现写成 startsWith(sensitive) 而漏掉 + "."，这两条会被误拦而测试变红
        Map<String, Object> props = levels("org.apache.hc.client5.httpfoo", "DEBUG");
        props.put("logging.level.software.amazon.awssdkx", "TRACE");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void sensitiveLoggersAtInfoOrAbovePass() {
        // INFO/WARN/ERROR/FATAL/OFF 均结构性关闭 debug 调用，加固未被削弱
        for (String level : List.of("INFO", "WARN", "ERROR", "FATAL", "OFF")) {
            Map<String, Object> props = levels(
                    "org.apache.hc.client5.http", level,
                    "org.apache.hc.client5.http.wire", level,
                    "software.amazon.awssdk", level);
            assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                    .as("敏感 logger 设为 %s 应放行", level)
                    .doesNotThrowAnyException();
        }
    }

    @Test
    void unrelatedBusinessLoggerDebugPasses() {
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(levels(
                "cn.iocoder.yudao", "DEBUG",
                "cn.iocoder.yudao.module.design.asset.CosObjectStorageAdapter", "TRACE"))))
                .doesNotThrowAnyException();
    }

    @Test
    void loggingGroupOfBusinessLoggersPasses() {
        // 组成员均为普通业务 logger：展开后无一命中敏感清单 → 放行（不误伤正常日志组用法）
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.design", "cn.iocoder.yudao.module.design,cn.iocoder.yudao.framework");
        props.put("logging.level.design", "DEBUG");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void loggingGroupOfAncestorLoggersPasses() {
        // 组成员是敏感 logger 的祖先：Boot setLogLevel(祖先, DEBUG) 不改变已钉 INFO 后代的有效级别
        // → 放行（与直接配置祖先口径一致，见 ancestorLoggerDebugPasses）
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.ancestors", "org.apache,org");
        props.put("logging.level.ancestors", "DEBUG");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void loggingGroupSetToInfoOrAbovePasses() {
        // 组级别为 INFO 及以上：即便组含敏感成员，isBelowInfo=false 直接放行（加固未削弱）
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos", "org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "INFO");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void noLoggingLevelConfiguredPasses() {
        // 未配置任何 logging.level.*：绑定结果为空 Map，不得抛错（默认部署路径）。
        // 刻意不用裸 new StandardEnvironment()：那会读到宿主真实的 systemProperties/systemEnvironment，
        // 令测试结果取决于 CI 或本机是否设了 LOGGING_LEVEL_*，违背本仓「测试须隔离宿主环境」的既有纪律
        //（见 ZhongshuWiringEnvironmentPostProcessorTest#envWith 同一处置）。
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(new HashMap<>())))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------------------------ 违例消息

    @Test
    void violationMessageNeverEchoesSecretValues() {
        // 铁律 1：违例消息只回显 logger 名与级别（均非机密），绝不回显密钥/签名样式内容。
        // 环境里同时存在 COS 密钥与预签名 URL 金丝雀，一旦消息拼接了它们即为泄露
        Map<String, Object> props = levels("org.apache.hc.client5.http.wire", "DEBUG");
        props.put("zhongshu.design.asset.storage.cos.secret-id", SECRET_ID_CANARY);
        props.put("zhongshu.design.asset.storage.cos.secret-key", SECRET_KEY_CANARY);
        props.put("zhongshu.design.asset.storage.cos.presigned-url", "https://bucket.cos.example/x?" + SIGNATURE_CANARY);

        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("logging.level.org.apache.hc.client5.http.wire")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain(SECRET_ID_CANARY, SECRET_KEY_CANARY, SIGNATURE_CANARY));
    }

    @Test
    void multipleViolationsAggregatedInSingleThrow() {
        // 多违例一次性抛全，便于运维一次看全（承 RealServiceWiringPolicy 同一纪律）；
        // 逐条抛出会让运维改一个重启一次
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(levels(
                "org.apache.hc.client5.http.wire", "TRACE",
                "software.amazon.awssdk", "DEBUG",
                "org.apache.hc.client5.http.headers", "DEBUG"))))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> {
                    String message = thrown.getMessage();
                    assertThat(message)
                            .contains("org.apache.hc.client5.http.wire")
                            .contains("software.amazon.awssdk")
                            .contains("org.apache.hc.client5.http.headers");
                    // 每条违例占一行「 - 」前缀；条数须与配置的违例数一致，证明未被提前截断
                    assertThat(message.split("\n - ", -1)).hasSize(4);
                });
    }

    // -------------------------------------------------------- 绑定失败净化

    @Test
    void invalidLevelValueBindingFailureIsSanitizedAndFailsClosed() {
        // codex round-7 P1②：把密钥误配为日志级别值。Spring 绑定 LogLevel 失败会抛含原始值的
        // ConversionFailedException（message 形如「No enum constant ...LogLevel.<原始值>」＋ cause 链）。
        // 守卫须净化：抛无 cause、不含原始值的安全异常，并 fail-closed（拒绝启动而非静默放行）。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.level.org.apache.hc.client5.http.wire", SECRET_KEY_CANARY);
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SECRET_KEY_CANARY);
                    assertThat(thrown.getCause()).isNull(); // 无 cause → 异常链不泄露原始值
                });
    }

    @Test
    void signedUrlAsLevelValueBindingFailureIsSanitized() {
        // 签名 URL 误配为级别值：同样净化，签名金丝雀既不入 message、也不经 cause 泄露
        Map<String, Object> props = new HashMap<>();
        props.put("logging.level.software.amazon.awssdk", "https://bucket.cos.example/x?" + SIGNATURE_CANARY);
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SIGNATURE_CANARY);
                    assertThat(thrown.getCause()).isNull();
                });
    }

    @Test
    void debugFlagWithUnresolvablePlaceholderIsSanitizedAndFailsClosed() {
        // codex round-9 P1①：debug 值含不可解析占位符时，environment.getProperty("debug") 会触发嵌套占位符解析并抛
        // org.springframework.util.PlaceholderResolutionException（javap 实证：原始值经 values 拼入 message「... in value <原始值>」，
        // 构造器仅调 IllegalArgumentException(String)、withValue 不传原异常作 cause——非 Throwable cause 链）。isSet 须净化：
        // 抛无 cause、不含金丝雀的安全异常并 fail-closed，杜绝机密经 debug/trace 读取路径的 message 泄露（铁律 1）。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", SECRET_KEY_CANARY + "${UNDEFINED_PLACEHOLDER_XYZ}");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SECRET_KEY_CANARY);
                    assertThat(thrown.getCause()).isNull();
                });
    }

    @Test
    void traceFlagWithUnresolvablePlaceholderIsSanitizedAndFailsClosed() {
        // codex round-9 P1①：trace 路径同理——签名 URL 金丝雀＋不可解析占位符，getProperty("trace") 抛含原始值异常，须净化。
        Map<String, Object> props = new HashMap<>();
        props.put("trace", "https://bucket.cos.example/x?" + SIGNATURE_CANARY + "${UNDEFINED_PLACEHOLDER_XYZ}");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SIGNATURE_CANARY);
                    assertThat(thrown.getCause()).isNull();
                });
    }

    @Test
    void loggingGroupIndexBindingBypassShouldBeBlocked() {
        // codex round-8 P1①（历史缺陷，已于 round-8 修复）：索引式 `logging.group.cos[0]=<敏感 logger>`＋`logging.level.cos=DEBUG`。
        // 旧实现曾只按逗号标量绑定 `logging.group.cos=x,y`，索引键被 Binder 归入 `cos[0]` 致 `rawGroups.get("cos")` 不命中而漏检；
        // 现以 Map<String,List<String>> 集合绑定（LOG_GROUP_BINDABLE）收敛索引式/YAML/逗号串为同一 List 形态，本例验证敏感成员仍被拦。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos[0]", "org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void debugFlagWithGroupExpansionBypassShouldBeBlocked() {
        // codex round-8 P1②（历史缺陷，已于 round-8 修复）：`debug=true`＋`logging.group.web=<敏感 logger>`、无任何 `logging.level.*`。
        // Boot 的 debug 标志沿 `initializeSpringBootLogging` 对内置目标（sql/web/org.springframework.boot）逐个 `configureLogLevel`，
        // 命中用户 web 组即展开成员设 DEBUG；旧实现只遍历 `logging.level.*` 映射、此路径无项可查而漏检。
        // 现经 detectImplicitDebugTraceLevels 补齐隐式目标，本例验证 web 组敏感成员被拦（codex 亲读字节码证实，见 .t13-1011-r8-out.txt）。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void debugFlagWithBusinessGroupPasses() {
        // codex round-8 P1② 放行对照：debug=true 时 Boot 只展开内置目标组名（sql/web/org.springframework.boot），
        // 用户自定义业务组名（design）不在内置目标内，Boot 不会因 debug 展开它 → 守卫不得误拦。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.design", "cn.iocoder.yudao.module.design,cn.iocoder.yudao.framework");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void debugFlagWithWebGroupOfBusinessLoggersPasses() {
        // 最具鉴别力的不误伤用例：debug=true 确实会展开内置目标 web 组，但用户把 web 组成员设为业务 logger（非敏感），
        // 展开后无一命中敏感清单 → 放行。证明守卫只在「内置目标组 ∩ 敏感成员」时才拦，而非见 debug+web 就拦。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.web", "cn.iocoder.yudao.module.design");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void debugFlagAloneWithoutAnyGroupPasses() {
        // debug=true 但无任何 logging.group：Boot 展开内置目标 sql/web/org.springframework.boot，用户未定义这些组 →
        // 判定名字本身（均非敏感 logger）→ 放行。debug 标志本身不构成敏感传输层泄露（默认开发路径不误伤）。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void traceFlagDoesNotExpandWebGroupPasses() {
        // 鉴别 TRACE 目标集合正确性（javap 实证 TRACE 目标＝org.springframework 等，不含 web）：web 是 DEBUG 内置目标，
        // 故 trace=true 时 Boot 不展开 logging.group.web → 即便 web 组含敏感 logger 也不泄露 → 守卫放行。
        // 若实现错误地让 trace 复用 DEBUG 目标（含 web），本用例会误拦变红。
        Map<String, Object> props = new HashMap<>();
        props.put("trace", true);
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------- logger 级覆盖（codex round-9 P1②）

    @Test
    void explicitInfoLevelOverridesImplicitGroupDebugOnSameLoggerPasses() {
        // codex round-9 P1②：debug=true 经 web 组把 wire 设 DEBUG，但显式 logging.level.wire=INFO 随后覆盖回 INFO
        // （Boot initializeFinalLoggingLevels：offset 18 先 initializeSpringBootLogging、offset 24 后 setLogLevels，同一 logger last-write-wins）
        // → 最终 wire=INFO 安全 → 放行。旧实现按「配置键」合并（wire→INFO 与 web→DEBUG 两键并存），展开 web 仍误报 wire 违例。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        props.put("logging.level.org.apache.hc.client5.http.wire", "INFO");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void laterExplicitGroupInfoOverridesImplicitDebugOnSameMemberPasses() {
        // codex round-9 P1②：两个组含同一敏感成员——debug 展开 web 组设 wire=DEBUG，显式 logging.level.safe=INFO（safe 组亦含 wire）
        // 随后把 wire 覆盖回 INFO → 最终安全放行。证明按实际 logger 名 last-write-wins，而非任一组命中即拦。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        props.put("logging.group.safe", "org.apache.hc.client5.http.wire");
        props.put("logging.level.safe", "INFO");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------- 隐式路径鉴别力（codex round-9 P2）

    @Test
    void traceFlagExpandingTraceTargetGroupToSensitiveLoggerFails() {
        // codex round-9 P2：补 TRACE 正向拦截——trace=true 时 Boot 展开 TRACE 内置目标（org.springframework 等）；
        // 用户把某 TRACE 目标名定义为含敏感成员的组，则敏感 logger 被设 TRACE → 必须拦（此前 33 例无 TRACE 目标映射敏感成员的拦截）。
        Map<String, Object> props = new HashMap<>();
        props.put("trace", true);
        props.put("logging.group.org.springframework", "org.apache.hc.client5.http.wire");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void debugFlagDoesNotExpandBusinessGroupWithSensitiveMemberPasses() {
        // codex round-9 P2：补 debug 放行鉴别力——debug=true 时 Boot 只展开内置目标组（sql/web/org.springframework.boot）；
        // 业务组 design 不在内置目标内，即便含敏感成员也不会因 debug 展开 → 放行。若守卫错误「见 debug 就展开所有组」，本例会被误拦变红。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("logging.group.design", "org.apache.hc.client5.http.wire");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void debugFlagEmptyStringIsTreatedAsSetAndBlocksSensitive() {
        // codex round-9 P2：isSet 语义鉴别——Boot isSet＝值非 null 且不等于 "false"（非 Boolean.parseBoolean），故 debug=""（空串）视为 set。
        // 空串仍触发隐式 DEBUG 目标展开 web 组 → 敏感成员被拦。若误用 parseBoolean（"" → false）则本例会漏拦变红。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", "");
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void debugFlagZeroIsTreatedAsSetAndBlocksSensitive() {
        // codex round-9 P2：isSet 语义鉴别——debug=0 亦非 "false"，Boot 视为 set（启用 debug）→ 展开 web 组 → 敏感成员被拦。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", "0");
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void debugFlagFalseIsNotSetAndDoesNotExpandWebGroupPasses() {
        // codex round-9 P2：isSet 语义对照——debug=false 恰为 "false"，Boot 视为未设 → 不展开内置目标 → web 组敏感成员不被设级别 → 放行。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", "false");
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void traceFlagOverridesDebugFlagAndUsesTraceTargetsPasses() {
        // codex round-9 P2：debug=true 与 trace=true 同设时 Boot 后判 trace 覆盖 debug（springBootLogging=TRACE），改用 TRACE 目标集
        // （不含 web）→ web 组不被展开 → 即便 web 组含敏感 logger 也放行。证明 trace 覆盖 debug 且目标集随级别切换。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", true);
        props.put("trace", true);
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void yamlListStyleMultiIndexGroupBindingBypassShouldBeBlocked() {
        // codex round-8 P1① 巩固：YAML 列表经 YamlPropertySourceLoader 展平为索引键（cos[0]/cos[1]），
        // 集合绑定须把它们收敛为同一 List 逐个成员判定 → 混合成员中敏感者命中即拦，业务成员不报。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos[0]", "cn.iocoder.yudao.module.design"); // 业务成员，不敏感 → 不报
        props.put("logging.group.cos[1]", "org.apache.hc.client5.http.wire"); // 敏感成员 → 拦
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("org.apache.hc.client5.http.wire")
                .satisfies(thrown -> assertThat(thrown.getMessage())
                        .doesNotContain("cn.iocoder.yudao.module.design")); // 业务成员不进违例消息
    }

    // ------------------------------------------- 标量逗号 vs 索引元素内含逗号（codex round-9 P1③）

    @Test
    void indexedGroupElementContainingCommaIsSingleLoggerNamePasses() {
        // codex round-9 P1③：索引式 logging.group.cos[0]="business,wire" —— Boot IndexedElementsBinder 把索引元素以 String 直接 add
        // （无二次逗号拆分），故 cos 组只有一个名为「business,wire」的怪 logger（不敏感）→ Boot 不泄露 → 放行。
        // 旧实现对该元素再 split(",") 拆出 wire → 误拦。本例证明改为「Binder 元素原样」后正确放行。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos[0]", "cn.example.business,org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "DEBUG");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    @Test
    void scalarCommaGroupValueIsSplitByBinderAndStillBlocksSensitive() {
        // codex round-9 P1③ 对照：标量逗号 logging.group.cos="business,wire"（无索引）—— Boot 经 DelimitedStringToCollectionConverter
        // 按逗号拆成两个成员 [business, wire]，wire 敏感 → 拦。证明「守卫不二次拆分」不影响标量路径（拆分由 Binder 负责，与索引式路径分野）。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos", "cn.example.business,org.apache.hc.client5.http.wire");
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    // ------------------------------------------- 空串组成员：hasMembers 基于原列表（codex round-10 P1①）

    @Test
    void emptyStringGroupMemberDoesNotFallBackToGroupNameAndStillBlocksSensitiveDebug() {
        // codex round-10 P1①（漏拦反例）：debug=true 经 web 组把 wire 设 DEBUG；随后 logging.level.wire=INFO 命中一个成员为 [""]
        // 的 wire 同名组。Boot：LoggerGroup.hasMembers() 在原列表 [""] 上判断为 true → 展开 setLogLevel("",INFO) →
        // getLoggerName("")="ROOT" → 只设 ROOT=INFO，wire 仍 DEBUG（敏感！）。守卫若过滤空串会误判无成员 → 回退把组名 wire
        // 本身设 INFO → 覆盖 DEBUG → 漏拦。本例断言仍拦（throws），证明保留空串成员后与 Boot 同构、不漏拦。
        Map<String, Object> props = new HashMap<>();
        props.put("debug", "true");
        props.put("logging.group.web", "org.apache.hc.client5.http.wire");
        props.put("logging.group.org.apache.hc.client5.http.wire[0]", "");
        props.put("logging.level.org.apache.hc.client5.http.wire", "INFO");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("org.apache.hc.client5.http.wire");
    }

    @Test
    void emptyStringMemberGroupNamedSensitiveResolvesToRootAndPasses() {
        // codex round-10 P1①（放行对照，与漏拦反例互为镜像）：组名恰为敏感 logger、但唯一成员是空串 [""]。
        // Boot：hasMembers([""])=true → 展开 setLogLevel("",DEBUG) → getLoggerName("")="ROOT" → 只把 ROOT 设 DEBUG，
        // 敏感 logger「org.apache.hc.client5.http.wire」本身未被设置（仍受 XML 钉的 INFO 保护）→ 无泄露 → 应放行（铁律 4 不查 root）。
        // 旧实现过滤空串 → 误判无成员 → 回退把组名（=敏感 logger）设 DEBUG → 误拦。本例证明修复后不误拦。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.org.apache.hc.client5.http.wire[0]", "");
        props.put("logging.level.org.apache.hc.client5.http.wire", "DEBUG");
        assertThatCode(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .doesNotThrowAnyException();
    }

    // ------------------------------------------- 违例消息不回显成员原值（codex round-10 P1②）

    @Test
    void signedUrlEmbeddedInGroupMemberValueIsNotEchoedInViolationMessage() {
        // codex round-10 P1②：组成员值内嵌签名 URL 金丝雀——logging.group.cos[0]="org.apache.hc.client5.http.wire,<签名URL>"。
        // 该整串命中敏感父项 org.apache.hc.client5.http（startsWith）→ 进违例消息。绑定成功、不经 sanitizedBindingFailure，
        // 故消息本身必须自净：只回显命中的敏感 logger 标识（固定清单安全值），绝不回显成员原值 → 签名 URL 不得入 message/cause/suppressed。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos[0]", "org.apache.hc.client5.http.wire,https://bucket.cos.example/x?" + SIGNATURE_CANARY);
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SIGNATURE_CANARY);
                    assertThat(thrown.getCause()).isNull();
                    assertThat(thrown.getSuppressed()).isEmpty();
                });
    }

    @Test
    void secretKeyEmbeddedInGroupMemberValueIsNotEchoedInViolationMessage() {
        // codex round-10 P1②：密钥金丝雀内嵌组成员值——logging.group.cos[0]="software.amazon.awssdk.<密钥>"，命中敏感父项
        // software.amazon.awssdk → 进违例消息。修复后消息只回显命中的敏感标识，密钥金丝雀不得入 message/cause/suppressed。
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos[0]", "software.amazon.awssdk." + SECRET_KEY_CANARY);
        props.put("logging.level.cos", "TRACE");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .satisfies(thrown -> {
                    assertThat(thrown.getMessage()).doesNotContain(SECRET_KEY_CANARY);
                    assertThat(thrown.getCause()).isNull();
                    assertThat(thrown.getSuppressed()).isEmpty();
                });
    }

    // ------------------------------------------- 最长命中项对混合大小写清单项也精确（codex round-11 P2①）

    @Test
    void groupMemberEqualToMixedCaseSensitiveItemReportsPreciseNameNotParent() {
        // codex round-11 P2①：SENSITIVE_LOGGERS 中唯一混合大小写项 software.amazon.awssdk.core.ResponseInputStream。
        // addViolationIfSensitive 把输入 toLowerCase 后比较，若清单项不归一则该项永不匹配（大小写不同）→ 只命中父项
        // software.amazon.awssdk → 违例消息丢失精确名。经日志组路径（viaGroup=true，消息取命中的 sensitive 而非 configuredName）
        // 才能鉴别：修复后比较时对清单项同样归一 → 命中最长（最具体）项 ResponseInputStream → 消息含精确名。
        // （直接配置 logging.level.software.amazon.awssdk.core.ResponseInputStream 走 configuredName、无法鉴别此缺口。）
        Map<String, Object> props = new HashMap<>();
        props.put("logging.group.cos", "software.amazon.awssdk.core.ResponseInputStream");
        props.put("logging.level.cos", "DEBUG");
        assertThatThrownBy(() -> SensitiveLoggingPolicy.validate(envWith(props)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("铁律 1")
                .hasMessageContaining("software.amazon.awssdk.core.ResponseInputStream");
    }
}
