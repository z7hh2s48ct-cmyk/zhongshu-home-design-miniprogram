package cn.iocoder.yudao.server.wiring;

import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.logging.LogLevel;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 敏感传输层日志级别守卫（B2 T13-10/11，铁律 1：密钥/签名 URL 绝不进日志）。
 *
 * <p>{@code logback-spring.xml} 已显式将 5 个敏感 logger 钉为 INFO，结构性关闭两条泄露面：AWS SDK 内部
 * {@code debug(Supplier,Throwable)}（abort 路径的原始异常可能含签名 URL），与 Apache HttpClient5 传输层
 * {@code DefaultManagedHttpClientConnection.onRequestSubmitted()} 的 {@code debug("{} >> {}", id, header)}
 * （逐个打印请求头且对 {@code Authorization: AWS4-HMAC-SHA256 Credential=<secret-id>, Signature=<sig>} 不脱敏）。
 *
 * <p><b>但配置层加固可被绕过</b>：Spring Boot 在 {@code logback-spring.xml} 加载<b>之后</b>由
 * {@code LoggingApplicationListener.setLogLevels()} 对 {@code logging.level.<name>} 逐个 {@code setLogLevel}，
 * <b>覆盖</b> XML 的显式声明。故一个 {@code LOGGING_LEVEL_ORG_APACHE_HC_CLIENT5_HTTP=DEBUG} 环境变量即令
 * COS secret-id 与请求签名落盘——此路径已用<b>真实进程环境对照实验</b>证实（变量注入进程环境后，
 * {@code Binder.get(env).bind("logging.level", mapOf(String, LogLevel))} 确实得到
 * {@code {org.apache.hc.client5.http.wire=DEBUG}}，与 Boot {@code setLogLevels} 所读完全一致）。
 * {@code SdkLoggingHardeningContractTest} 只在构建期锁定 XML，运行期无人拦截，
 * 本守卫补上这一环：启动期快速失败。
 *
 * <p><b>为何查 Environment 属性而非 logger 的 {@code isDebugEnabled()}</b>（javap 实证 Boot 3.5.15／Logback 1.5.34）：
 * {@code EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER = -2147483638}（HIGHEST_PRECEDENCE+10）
 * 早于 {@code LoggingApplicationListener.DEFAULT_ORDER = -2147483628}（+20），即本守卫运行时
 * {@code logback-spring.xml} <b>尚未加载</b>、{@code logging.level.*} 尚未应用。更需注意：更早的
 * {@code ApplicationStartingEvent} 已触发 {@code LoggingApplicationListener → LogbackLoggingSystem.beforeInitialize()}
 * 安装 {@code SUPPRESS_ALL_FILTER}（{@code TurboFilter} 恒返 {@code FilterReply.DENY}），故本阶段任意 logger 的
 * {@code isDebugEnabled()} 反映的是<b>启动抑制态而非最终运行态</b>——据其判定既会因抑制期恒 {@code false} 而漏报，
 * 也无法预测 {@code logback-spring.xml} 与 {@code logging.level.*} 应用后的最终级别。Environment 属性才是本阶段
 * 唯一可靠的事实源：它如实记录「Boot 随后将会应用的级别」。（此前实现初稿称此阶段 {@code isDebugEnabled()} 恒为
 * {@code true}，经字节码核实<b>失实</b>，已订正为上述抑制态说明；选 Environment 的结论不变。）
 *
 * <p><b>为何用 {@code Binder} 而非遍历 PropertySource 键</b>：与 Boot 完全同构（javap 实证
 * {@code initializeFinalLoggingLevels} 先 {@code bindLoggerGroups(env)}＝{@code Binder.bind(LOGGING_GROUP,
 * STRING_STRINGS_MAP)}，再 {@code setLogLevels}＝{@code Binder.bind(LOGGING_LEVEL, STRING_LOGLEVEL_MAP)}），
 * 故守卫拦截的正是 Boot 将会应用的；松散绑定的环境变量形式（{@code LOGGING_LEVEL_*}）一并覆盖，零漏网。
 *
 * <p><b>为何必须展开日志组 {@code logging.group.*}</b>（codex round-7 P1①，javap 实证 Boot {@code configureLogLevel}）：
 * Boot 对每个 {@code logging.level.<name>} 先查 {@code loggerGroups.get(name)}（内部普通 {@code Map.get}，精确、大小写
 * 敏感匹配），命中且 {@code hasMembers()} 时<b>不对 {@code name} 本身、而对其每个成员</b> {@code setLogLevel}。故
 * {@code logging.group.cos=org.apache.hc.client5.http.wire} ＋ {@code logging.level.cos=DEBUG} 会令 Boot 把敏感 logger
 * 直接改为 DEBUG、覆盖 XML 钉级别，而只查 {@code logging.level} 键（{@code cos}）会漏掉。守卫遂<b>镜像</b>此语义：
 * 命中组名则展开成员逐个判定（成员为祖先仍放行，与直接配置口径一致），未命中则按 logger 名本身判定。
 *
 * <p><b>为何必须纳入 {@code debug=true}/{@code trace=true} 隐式路径</b>（codex round-8 P1②，javap 实证 Boot
 * {@code initializeSpringBootLogging}）：Boot 的 {@code debug}/{@code trace} 标志<b>不经任何 {@code logging.level.*}</b>，
 * 而是对 {@code SPRING_BOOT_LOGGING_LOGGERS} 内置目标名（DEBUG＝{@code sql,web,org.springframework.boot}；TRACE＝
 * {@code org.springframework,org.apache.tomcat,...}）逐个 {@code configureLogLevel}——命中用户 {@code logging.group.<名>}
 * 即展开成员设该级别。故 {@code debug=true}＋{@code logging.group.web=<敏感 logger>} 会绕过「只查 {@code logging.level.*}」
 * 的守卫。本类遂镜像该表与 {@code isSet} 语义（见 {@link #detectImplicitDebugTraceLevels}），把隐式目标补成级别项一并判定；
 * 组名不在内置目标内的业务组、以及 {@code logging.level.root=DEBUG} 均不受影响（不误伤运维诊断）。
 *
 * <p><b>为何按「实际 logger 名」而非「配置键」判定最终级别</b>（codex round-9 P1②，javap 实证 Boot
 * {@code initializeFinalLoggingLevels} 顺序）：Boot 先 {@code initializeSpringBootLogging}（offset 18，应用 debug/trace
 * 内置目标）、后 {@code setLogLevels}（offset 24，应用 {@code logging.level.*}），二者都经 {@code configureLogLevel} 逐个
 * {@code setLogLevel}，故同一实际 logger 名<b>后写覆盖先写</b>。守卫遂镜像此序：先把隐式目标、再把显式配置展开到实际
 * logger 名（{@link #applyConfiguredLevel}），在最终级别上判定。由此 {@code debug=true}＋{@code logging.group.web=wire}＋
 * {@code logging.level.wire=INFO}（Boot 最终 wire=INFO 安全）被正确放行，而非因保留 {@code web→DEBUG} 键展开误拦。
 *
 * <p><b>为何只拦「自身与后代」而不拦「祖先」</b>：Logback 有效级别取最近的已声明级别祖先，而
 * {@code logback-spring.xml} 已显式声明这 5 个 logger 为 INFO，故 {@code logging.level.org.apache=DEBUG}
 * 这类<b>祖先</b>设置不会改变其有效级别（{@code handleParentLevelChange} 遇自身非 null 的 level 即停止继承更新），
 * 实际不泄露；拦它只会误报并阻碍运维排查其它 Apache 组件。而 {@code logging.level.org.apache.hc.client5.http=DEBUG}
 * （自身）与 {@code ...client5.http.wire=DEBUG}（后代）会被 Boot 直接 {@code setLogLevel} 覆盖 XML，确会泄露，必须拦。
 *
 * <p><b>刻意不拦 {@code logging.level.root=DEBUG}</b>：XML 已钉住 5 个敏感 logger，root=DEBUG 不覆盖它们，
 * 运维仍可开 root DEBUG 排查业务问题。本守卫只关死「显式开敏感传输层 DEBUG」这<b>一类</b>绕过，不牺牲正常诊断。
 *
 * <p><b>刻意不拦「运行时 {@code LoggingSystem.setLogLevel()} 调用」</b>：那需要有人在代码里主动写调用，
 * 属代码评审范畴而非配置绕过，且本守卫所在阶段日志系统尚未初始化、无从拦截。边界如实记录于交付文档。
 *
 * <p>违例消息只回显 logger 名与级别（均非机密），<b>绝不回显任何密钥值</b>；多违例聚合后一次性抛出，
 * 便于运维一次看全（承 {@link RealServiceWiringPolicy} 同一纪律）。
 */
public final class SensitiveLoggingPolicy {

    /**
     * 敏感 logger：DEBUG/TRACE 下会输出凭据、签名 URL 或含 Authorization 的请求头（铁律 1）。
     * 本清单与 {@code logback-spring.xml} 显式钉为 INFO 的 5 个 logger、以及
     * {@code SdkLoggingHardeningContractTest} 的 {@code PINNED_SDK_LOGGERS} ＋
     * {@code PINNED_HTTP_TRANSPORT_LOGGERS} 两数组<b>必须保持一致</b>（三方同源，任一处增删须同步）。
     */
    private static final List<String> SENSITIVE_LOGGERS = List.of(
            "software.amazon.awssdk",
            "software.amazon.awssdk.core.ResponseInputStream",
            "org.apache.hc.client5.http",
            "org.apache.hc.client5.http.headers",
            "org.apache.hc.client5.http.wire");

    /** Boot 应用日志级别的绑定前缀，与 {@code LoggingApplicationListener.LOGGING_LEVEL} 同值。 */
    private static final String LOGGING_LEVEL_PREFIX = "logging.level";

    /**
     * Boot 日志组的绑定前缀，与 {@code LoggingApplicationListener.LOGGING_GROUP} 同值。
     * {@code logging.group.<组名>=<逗号分隔 logger 名>}；Boot 对 {@code logging.level.<组名>} 命中组时展开成员逐个
     * {@code setLogLevel}（见类 javadoc「为何必须展开日志组」）。守卫以 {@code Map<String,List<String>>} 绑定
     * （{@link #LOG_GROUP_BINDABLE}，与 Boot {@code STRING_STRINGS_MAP} 同构），标量逗号由 Binder 的
     * {@code DelimitedStringToCollectionConverter}（Boot，经 {@code BindConverter} 首 delegate 注册）拆分、索引元素原样保留，
     * <b>守卫不再二次拆分</b>（见 {@link #resolveGroupMembers}）。
     */
    private static final String LOGGING_GROUP_PREFIX = "logging.group";

    /**
     * Boot {@code STRING_STRINGS_MAP} 的同构 Bindable：{@code Map<String,List<String>>}，经
     * {@code ResolvableType.forClassWithGenerics(MultiValueMap,String,String).asMap()} ＋ {@code Bindable.of} 构造
     * （javap 实证 Boot {@code LoggingApplicationListener} static 块 offset 28-54：与 {@code STRING_STRINGS_MAP} 逐指令同构），
     * 以支持逗号分隔、索引式（{@code logging.group.cos[0]=x}）与 YAML 列表等全部 Boot 集合输入形式（codex round-8 P1①）。
     */
    private static final Bindable<Map<String, List<String>>> LOG_GROUP_BINDABLE = Bindable.of(
            org.springframework.core.ResolvableType
                    .forClassWithGenerics(org.springframework.util.MultiValueMap.class, String.class, String.class)
                    .asMap());

    /**
     * Boot {@code SPRING_BOOT_LOGGING_LOGGERS} 的逐值镜像（javap 实证 {@code LoggingApplicationListener} static 块
     * offset 168-276）：{@code debug=true}/{@code trace=true} 时 {@code initializeSpringBootLogging} 遍历本表对应级别的内置
     * 目标名，逐个 {@code configureLogLevel}——命中用户 {@code logging.group.<名>} 则展开成员设该级别（codex round-8 P1②）。
     * <ul>
     *   <li>{@code DEBUG → [sql, web, org.springframework.boot]}</li>
     *   <li>{@code TRACE → [org.springframework, org.apache.tomcat, org.apache.catalina, org.eclipse.jetty, org.hibernate.tool.hbm2ddl]}</li>
     * </ul>
     * 故 {@code debug=true}＋{@code logging.group.web=<敏感 logger>}（无任何 {@code logging.level.*}）会被 Boot 展开设 DEBUG，
     * 只查 {@code logging.level.*} 的守卫会漏——本表用于补齐这条隐式路径。
     */
    private static final Map<LogLevel, List<String>> SPRING_BOOT_LOGGING_LOGGERS = Map.of(
            LogLevel.DEBUG, List.of("sql", "web", "org.springframework.boot"),
            LogLevel.TRACE, List.of("org.springframework", "org.apache.tomcat", "org.apache.catalina",
                    "org.eclipse.jetty", "org.hibernate.tool.hbm2ddl"));

    private SensitiveLoggingPolicy() {
    }

    /**
     * 校验 {@code logging.level.*} 未把任一敏感 logger（自身或后代）放开至 DEBUG/TRACE；违例聚合后一次性抛出。
     * 命中 {@code logging.group.*} 定义的日志组时，<b>镜像 Boot {@code configureLogLevel} 语义展开组成员逐个判定</b>
     * （见类 javadoc）。绑定失败按 fail-closed 净化处理（见 {@link #sanitizedBindingFailure}）。
     *
     * @param environment 已加载 ConfigData 的环境；调用方须保证其 order 晚于
     *                    {@code ConfigDataEnvironmentPostProcessor}，否则读不到 profile 内的 {@code logging.level.*}
     */
    public static void validate(Environment environment) {
        Map<String, LogLevel> configuredLevels = bindLogLevelMap(environment);
        Map<String, List<String>> rawGroups = bindGroupMapToCollection(environment);

        // debug=true/trace=true 的隐式级别路径（codex round-8 P1②）：镜像 Boot initializeSpringBootLogging，
        // 把 SPRING_BOOT_LOGGING_LOGGERS 内置目标名按 debug/trace 级别补成隐式级别项（见 detectImplicitDebugTraceLevels）。
        Map<String, LogLevel> implicitLevels = detectImplicitDebugTraceLevels(environment);
        
        // 镜像 Boot initializeFinalLoggingLevels 的应用顺序，在「实际 logger 名」上解析最终级别（codex round-9 P1②）：
        // ① initializeSpringBootLogging（offset 18）——debug/trace 内置目标名先应用；
        // ② setLogLevels（offset 24）——logging.level.* 后应用，覆盖①在同一实际 logger 名上的结果（last-write-wins）。
        // 由此「debug 经 web 组把 wire 设 DEBUG、但显式 logging.level.wire=INFO 又覆盖回 INFO」最终安全 → 正确放行；
        // 旧实现按「配置键」合并（wire→INFO 与 web→DEBUG 两键并存）会展开 web 误报 wire 违例。
        Map<String, LevelProvenance> resolvedLoggerLevels = new LinkedHashMap<>();
        implicitLevels.forEach((targetName, level) ->
                applyConfiguredLevel(resolvedLoggerLevels, rawGroups, targetName, level));
        configuredLevels.forEach((configuredName, level) ->
                applyConfiguredLevel(resolvedLoggerLevels, rawGroups, configuredName, level));

        // 在最终解析出的实际 logger 名上判定：敏感（自身/后代）且低于 INFO 才违例；INFO 及以上结构性关闭 debug 输出。
        List<String> violations = new ArrayList<>();
        resolvedLoggerLevels.forEach((loggerName, provenance) -> {
            if (isBelowInfo(provenance.level)) {
                addViolationIfSensitive(violations, provenance.configuredName, loggerName,
                        provenance.level, provenance.viaGroup);
            }
        });

        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "敏感传输层日志级别校验失败（B2 T13-10/11，铁律 1）：\n - " + String.join("\n - ", violations));
        }
    }

    /**
     * 镜像 Boot {@code configureLogLevel(name, level)}：{@code loggerGroups.get(name)} 命中且有成员 → 对每个成员
     * {@code setLogLevel}；否则对 {@code name} 本身。结果写入 {@code resolved}，同一实际 logger 名 last-write-wins
     * （调用方按 Boot 顺序先隐式后显式，故显式覆盖隐式）。同时记录触发该级别的配置键，供违例消息回显。
     */
    private static void applyConfiguredLevel(Map<String, LevelProvenance> resolved,
            Map<String, List<String>> rawGroups, String configuredName, LogLevel level) {
        List<String> members = resolveGroupMembers(rawGroups.get(configuredName));
        if (members.isEmpty()) {
            resolved.put(configuredName, new LevelProvenance(level, configuredName, false));
        } else {
            for (String member : members) {
                resolved.put(member, new LevelProvenance(level, configuredName, true));
            }
        }
    }

    /** 某实际 logger 名的最终级别及其来源配置键（组名/logger 名/内置目标名），仅供违例消息回显，不含任何机密值。 */
    private static final class LevelProvenance {
        private final LogLevel level;
        private final String configuredName;
        private final boolean viaGroup;

        private LevelProvenance(LogLevel level, String configuredName, boolean viaGroup) {
            this.level = level;
            this.configuredName = configuredName;
            this.viaGroup = viaGroup;
        }
    }

    /**
     * 返回日志组的实际成员 logger 名，<b>直接使用 Binder 绑定的元素、不再二次拆分或修剪，且保留空串成员</b>：
     * <ul>
     *   <li><b>不二次拆分</b>（codex round-9 P1③）：标量逗号串 {@code logging.group.cos=x,y} 已由 Boot 的
     *       {@code DelimitedStringToCollectionConverter}（经 {@code BindConverter} 首个 delegate {@code TypeConverterConversionService}
     *       构造器调用 {@code ApplicationConversionService.addDelimitedStringConverters} 注册、默认分隔符逗号；Spring 的
     *       {@code StringToCollectionConverter} 位于 spring-core、非本路径）拆成 {@code [x, y]}；而索引式
     *       {@code logging.group.cos[0]=x,y} 的元素被 {@code IndexedElementsBinder} 以 String 直接 {@code add}，保持为含逗号的
     *       <b>单个</b>怪 logger 名（Boot {@code LoggerGroup.configureLogLevel} 的 {@code members.forEach} 亦不二次拆分）。
     *       守卫若再 {@code split(",")} 会把后者误拆出敏感成员而误拦。</li>
     *   <li><b>保留空串成员</b>（codex round-10 P1①）：javap 实证 Boot {@code LoggerGroup} 构造器直接复制原列表、
     *       {@code hasMembers()}＝{@code !members.isEmpty()} 在<b>原列表</b>上判断（不过滤空串），{@code configureLogLevel} 的
     *       {@code members.forEach} 对<b>每个成员含 {@code ""}</b> 调 setter；{@code IndexedElementsBinder.bindIndexed} 把空值
     *       {@code logging.group.x[0]=} 直接 {@code add} 成 {@code [""]}。故 {@code [""]} 组 hasMembers=TRUE、被展开为
     *       {@code setLogLevel("", level)}，而 {@code LogbackLoggingSystem.getLoggerName("")}＝{@code "ROOT"} → 实际只设 ROOT。
     *       若守卫过滤掉 {@code ""} 会误判「无成员」→ 回退把<b>组名本身</b>（可能是敏感 logger）设该级别，覆盖此前经他组设的
     *       敏感 DEBUG → <b>漏拦</b>。空串成员在此展开为 {@code resolved[""]}，因 {@code isSelfOrDescendant("", 敏感)} 恒 false
     *       （等价 Boot 的 ROOT，铁律 4 不查 root）而永不命中，语义与 Boot 一致。</li>
     * </ul>
     * 仅跳过 null 元素、其余原样返回，确保「守卫所拦＝Boot 将应用」。承 Boot {@code STRING_STRINGS_MAP}＝
     * {@code Map<String,List<String>>} 语义（codex round-8 P1①）：索引式、YAML 列表 {@code - x} 与逗号串均绑定为同一 List 形态。
     */
    private static List<String> resolveGroupMembers(List<String> rawMembers) {
        if (rawMembers == null || rawMembers.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> members = new ArrayList<>();
        for (String member : rawMembers) {
            if (member != null) { // 保留空串成员以镜像 Boot hasMembers()（过滤空串会致漏拦，codex round-10 P1①）
                members.add(member);
            }
        }
        return members;
    }

    /**
     * 若 {@code effectiveLoggerName}（组成员或直接配置的 logger 名）是敏感 logger 自身或后代，追加一条违例。
     * 同一 effective logger 命中多个敏感项时只报一次（取最长命中项后统一追加），避免重复噪声。
     *
     * @param configuredName      {@code logging.level.<configuredName>} 的原始键（组名或 logger 名）
     * @param effectiveLoggerName Boot 实际会 {@code setLogLevel} 的 logger 名（组成员或 configuredName 本身）
     * @param viaGroup            是否经日志组展开命中（决定违例消息措辞）
     */
    private static void addViolationIfSensitive(List<String> violations, String configuredName,
            String effectiveLoggerName, LogLevel level, boolean viaGroup) {
        String normalized = effectiveLoggerName.toLowerCase(Locale.ROOT);
        String mostSpecific = null;
        for (String sensitive : SENSITIVE_LOGGERS) {
            // 取最长（最具体）命中项：干净成员 org.apache.hc.client5.http.wire 命中精确项而非笼统父项
            // org.apache.hc.client5.http，令违例消息精确定位到实际敏感 logger；而内嵌 URL/密钥的成员值
            // 「org.apache.hc.client5.http.wire,https://…X-Amz-Signature=…」因 wire 后是逗号非「.」，只命中父项，
            // 故回显的安全常量取自固定清单、绝不含原始成员值（codex round-10 P1②）。比较时对清单项同样按
            // toLowerCase(Locale.ROOT) 归一（输入已归一），令唯一混合大小写项 software.amazon.awssdk.core.ResponseInputStream
            // 作组成员时也命中精确项、而非只命中父项 software.amazon.awssdk 丢失精确名；mostSpecific 仍存原始常量供安全回显（codex round-11 P2①）。
            if (isSelfOrDescendant(normalized, sensitive.toLowerCase(Locale.ROOT))
                    && (mostSpecific == null || sensitive.length() > mostSpecific.length())) {
                mostSpecific = sensitive;
            }
        }
        if (mostSpecific != null) {
            violations.add(buildViolationMessage(configuredName, level, mostSpecific, viaGroup));
        }
    }

    /**
     * 违例消息只回显<b>属性键（组名/logger 名）</b>、级别、与命中的<b>敏感 logger 标识（取自固定清单 {@link #SENSITIVE_LOGGERS}）</b>，
     * 均非机密；<b>绝不回显组成员原值或任何密钥/签名 URL</b>（codex round-10 P1②）：组成员值可能内嵌
     * {@code org.apache.hc.client5.http.wire,https://…X-Amz-Signature=…}，命中敏感前缀后若把 {@code effectiveLoggerName}
     * （未净化的成员原值）回显进消息即违反铁律 1——且此路径绑定成功、不经 {@link #sanitizedBindingFailure}，故消息本身必须自净。
     * viaGroup 分支因此改用 {@code sensitive}（由 {@link #addViolationIfSensitive} 选出的<b>最长命中项</b>、取自固定清单的安全常量）而非成员原值。附运维指引。
     */
    private static String buildViolationMessage(String configuredName, LogLevel level, String sensitive, boolean viaGroup) {
        String target = viaGroup
                ? String.format("logging.level.%s=%s（%s 是日志组，Boot 会展开其成员逐个 setLogLevel；命中敏感传输层 logger「%s」的自身或后代）",
                        configuredName, level, configuredName, sensitive)
                : String.format("logging.level.%s=%s", configuredName, level);
        return String.format(
                "%s：该 logger 是敏感传输层 logger「%s」的自身或后代，DEBUG/TRACE 会把"
                        + "凭据/签名 URL/含 Authorization 的请求头写入日志（铁律 1 违规）。"
                        + "请移除该配置或改为 INFO 及以上。如需排查业务问题，可设 logging.level.root=DEBUG"
                        + "（本加固不受 root 影响）；如确需开启敏感传输层 DEBUG，须显式修改 logback-spring.xml"
                        + " 并同步调整 SdkLoggingHardeningContractTest——这一摩擦是有意为之。",
                target, sensitive);
    }

    /**
     * 与 Boot {@code setLogLevels} 同构地绑定 {@code logging.level.*} 为 {@code Map<String,LogLevel>}。
     * <b>绑定失败按铁律 1 净化</b>（见 {@link #sanitizedBindingFailure}）。
     */
    private static Map<String, LogLevel> bindLogLevelMap(Environment environment) {
        try {
            return Binder.get(environment)
                    .bind(LOGGING_LEVEL_PREFIX, Bindable.mapOf(String.class, LogLevel.class))
                    .orElseGet(Collections::emptyMap);
        } catch (RuntimeException ex) {
            throw sanitizedBindingFailure(LOGGING_LEVEL_PREFIX + ".*",
                    "合法日志级别（TRACE/DEBUG/INFO/WARN/ERROR/FATAL/OFF）");
        }
    }

    /**
     * 与 Boot {@code bindLoggerGroups} <b>同构</b>地绑定 {@code logging.group.*} 为 {@code Map<组名,成员列表>}
     * （{@link #LOG_GROUP_BINDABLE}＝{@code Map<String,List<String>>}，javap 实证与 Boot {@code STRING_STRINGS_MAP} 逐指令一致）。
     * 由此索引式 {@code logging.group.cos[0]=x}、YAML 列表、逗号串全部收敛为同一 List 形态，杜绝 codex round-8 P1① 的标量键漏检。
     * <b>绑定失败按铁律 1 净化</b>（见 {@link #sanitizedBindingFailure}）。
     */
    private static Map<String, List<String>> bindGroupMapToCollection(Environment environment) {
        try {
            return Binder.get(environment)
                    .bind(LOGGING_GROUP_PREFIX, LOG_GROUP_BINDABLE)
                    .orElseGet(Collections::emptyMap);
        } catch (RuntimeException ex) {
            throw sanitizedBindingFailure(LOGGING_GROUP_PREFIX + ".*", "逗号分隔的 logger 名列表");
        }
    }

    /**
     * 镜像 Boot {@code initializeEarlyLoggingLevel}＋{@code initializeSpringBootLogging}（javap 实证），把
     * {@code debug=true}/{@code trace=true} 的<b>隐式</b>级别路径补成 {@code Map<内置目标名,级别>}，交由 {@link #validate}
     * 主循环按 {@code logging.group.*} 展开判定（codex round-8 P1②）。无 debug/trace 时返回空表（默认部署路径不受影响）。
     *
     * <p><b>严格镜像要点</b>：① {@code isSet} 语义＝值非 null 且不等于字符串 {@code "false"}（Boot 字节码 {@code isSet} 原样，
     * <b>非</b> {@code Boolean.parseBoolean}，故 {@code debug=}（空）、{@code debug=0} 亦视为 set）；② trace 覆盖 debug
     * （Boot 先判 debug 再判 trace，后者覆写 {@code springBootLogging}）；③ 目标名集合取自 {@link #SPRING_BOOT_LOGGING_LOGGERS}。
     * 由此 {@code debug=true}＋{@code logging.group.web=<敏感>} 会因内置目标含 {@code web} 而被展开拦截；而 {@code debug=true}＋
     * 业务组（组名不在内置目标内）不被展开、{@code logging.level.root=DEBUG} 亦不受影响（均放行，不误伤运维诊断）。
     */
    private static Map<String, LogLevel> detectImplicitDebugTraceLevels(Environment environment) {
        LogLevel implicit = null;
        if (isSet(environment, "debug")) {
            implicit = LogLevel.DEBUG;
        }
        if (isSet(environment, "trace")) {
            implicit = LogLevel.TRACE; // 镜像 Boot：trace 后判，覆盖 debug
        }
        if (implicit == null) {
            return Collections.emptyMap();
        }
        Map<String, LogLevel> implicitLevels = new LinkedHashMap<>();
        for (String target : SPRING_BOOT_LOGGING_LOGGERS.getOrDefault(implicit, Collections.emptyList())) {
            implicitLevels.put(target, implicit);
        }
        return implicitLevels;
    }

    /**
     * Boot {@code LoggingApplicationListener.isSet} 的逐语义镜像：属性存在且值不等于字符串 {@code "false"} 即为真。
     * <b>读取失败按铁律 1 净化</b>（codex round-9 P1①）：{@code getProperty} 会触发嵌套占位符解析
     * （{@code PropertySourcesPropertyResolver.getProperty} → {@code resolveNestedPlaceholders}），
     * {@code debug=${SECRET}${UNDEFINED}} 不可解析时 Spring 抛 {@code org.springframework.util.PlaceholderResolutionException}
     * （javap 实证：其私有构造器仅调 {@code IllegalArgumentException(String)}、{@code buildMessage} 把原始值经 {@code values}
     * 拼入 message「... in value <原始值>」，{@code withValue} 亦不传原异常作 cause——即<b>原始值进入 message/values 而非
     * Throwable cause 链</b>）。故此处 catch 后改抛无 cause、不回显原始值的安全异常并 fail-closed（与
     * {@link #sanitizedBindingFailure} 同一纪律），杜绝机密经 debug/trace 读取路径的 message 泄露。
     */
    private static boolean isSet(Environment environment, String name) {
        String value;
        try {
            value = environment.getProperty(name);
        } catch (RuntimeException ex) {
            throw sanitizedBindingFailure(name, "可解析的 debug/trace 布尔标志（不含未定义占位符）");
        }
        return value != null && !"false".equals(value);
    }

    /**
     * 绑定失败净化（codex round-7 P1②，铁律 1）。若 {@code logging.level.<name>} 被误配为 {@code ${secret}} 占位符、
     * 解析出非法级别值，Spring 的 {@code Binder.bind} 会抛 {@code ConversionFailedException}，其 message（形如
     * {@code No enum constant ...LogLevel.<原始值>}）与 cause <b>携带原始值</b>——经 EPP → {@code SpringApplication}
     * 启动失败链落盘即泄露密钥/签名 URL。故本守卫<b>不保留原异常 cause/suppressed、不回显原始值</b>，改抛无 cause 的
     * 安全异常；又因绑定失败时无法确认敏感 logger 是否被放开，按 <b>fail-closed</b> 拒绝启动。附带效果：本守卫在 Boot
     * {@code setLogLevels} 之前抢先以净化消息失败，阻止 Boot 自身泄露原始值的转换异常。
     *
     * @return 供调用点 {@code throw} 的安全异常（刻意返回而非内部直接抛，令 {@code catch} 分支语义清晰）
     */
    private static IllegalStateException sanitizedBindingFailure(String prefix, String expected) {
        return new IllegalStateException(String.format(
                "%s 绑定失败（B2 T13-10/11，铁律 1）：某配置项的值不是%s。为防止误配为 ${敏感值} 时密钥/签名 URL 经 "
                        + "Spring 转换异常的 message 与 cause 泄露，本守卫已净化：不回显原始值、不保留异常链（cause 为 null）。"
                        + "请检查 %s 各项的值是否合法。因绑定失败时无法确认敏感 logger 未被放开，按 fail-closed 拒绝启动。",
                prefix, expected, prefix));
    }

    /** TRACE/DEBUG 视为「低于 INFO」；INFO/WARN/ERROR/FATAL/OFF 均结构性关闭 debug 输出。 */
    private static boolean isBelowInfo(LogLevel level) {
        return level == LogLevel.TRACE || level == LogLevel.DEBUG;
    }

    /**
     * 判定 {@code loggerName} 是否为敏感 logger 自身或其后代——只有这两种会被 Boot 直接 {@code setLogLevel}
     * 覆盖 XML 钉级别（祖先不会，见类 javadoc）。
     *
     * <p>调用前必须先将 {@code loggerName} 小写归一，这是<b>必需而非冗余</b>：实测表明 Binder 将
     * {@code logging.level.*} 绑定为 Map 时<b>保留属性名原始大小写</b>（{@code logging.level.ORG.APACHE.HC.CLIENT5.HTTP=DEBUG}
     * 的绑定结果 key 就是 {@code ORG.APACHE.HC.CLIENT5.HTTP}），不归一则无法与全小写的敏感清单匹配，
     * 本方法会返回语义错误的 {@code false} 而漏报。
     *
     * <p>而「是否该拦大写变体」本身属<b>保守加严</b>：Logback 的 logger 名大小写敏感，
     * {@code ORG.APACHE.HC.CLIENT5.HTTP} 在 Logback 中是另一个 logger，Boot 对它 {@code setLogLevel(DEBUG)}
     * 并不会削弱已钉为 INFO 的 {@code org.apache.hc.client5.http}，故实际不泄露。但此类写法本身即异常配置，
     * 按「铁律 1 优先于诊断便利、宁可误报不可漏报」的既有取舍仍一并拒绝。
     */
    private static boolean isSelfOrDescendant(String normalizedLoggerName, String sensitive) {
        return normalizedLoggerName.equals(sensitive) || normalizedLoggerName.startsWith(sensitive + ".");
    }
}
