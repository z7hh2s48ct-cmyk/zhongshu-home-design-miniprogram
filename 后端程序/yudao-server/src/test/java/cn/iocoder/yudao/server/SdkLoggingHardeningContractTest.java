package cn.iocoder.yudao.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B2 T13-10/11：AWS SDK 与 Apache HttpClient5 传输层日志级别加固的配置契约测试（无 Spring 上下文、无 Docker、无真实消费）。
 *
 * <p>铁律 1 要求密钥/签名 URL 绝不进日志。COS 适配器的 abort 路径存在一条 SDK 内部泄露通道：
 * <pre>
 *   CosObjectStorageAdapter.abortQuietly()
 *     -&gt; ResponseInputStream.abort()          // 34-41: IoUtils.closeQuietlyV2(in, log)
 *     -&gt; IoUtils.closeQuietlyV2(closeable, log) // 5: close(); 13: catch Exception -&gt; astore_2
 *     -&gt; Logger.debug(Supplier, Throwable)      // Throwable = 底层 close() 抛出的原始异常
 * </pre>
 * 泄露载体是该 Throwable 的消息/异常链（可能含签名 URL），而 Supplier 只返回常量
 * "Ignore failure in closing the Closeable"。{@code Logger.debug(Supplier, Throwable)} 内部
 * 先判 {@code isDebugEnabled()} 再求值，故对应 logger 有效级别 &gt;= INFO 时，Supplier 不求值、
 * Throwable 不交给任何 appender，泄露路径为结构性关闭。
 *
 * <p>该 logger 名由 {@code ResponseInputStream.static{}} 中的
 * {@code Logger.loggerFor(ResponseInputStream.class)} -> {@code LoggerFactory.getLogger(Class)}
 * 决定，即该类全限定名。
 *
 * <p><b>第二条泄露面（codex round-5 P1）：Apache HttpClient5 传输层</b>。AWS SDK v2 2.54.7 默认同步 HTTP
 * 客户端为 apache5-client，{@code CosObjectStorageAdapter.buildS3()} 未覆写 httpClient 即用之。其
 * {@code DefaultManagedHttpClientConnection.onRequestSubmitted()} 经 logger
 * {@code org.apache.hc.client5.http.headers}/{@code .wire} 在 DEBUG 下逐个打印请求头（含 SigV4 的
 * {@code Authorization: ... Credential=<secret-id>, Signature=<sig>}）且不脱敏。这两个 logger 不在
 * {@code software.amazon.awssdk} 命名空间下，故仅钉 AWS logger 不能封堵——{@code logging.level.root=DEBUG}
 * 即令其继承 DEBUG 而泄露 COS secret-id 与请求签名（铁律 1）。故须一并钉 {@code org.apache.hc.client5.http}。
 *
 * <p><b>三测试策略（配置解析「声明」+ 两条运行时「行为」，共用同一 {@code logback-spring.xml} 事实源）</b>：
 * <ul>
 *   <li>{@link #productionLogbackPinsAwsSdkLoggersToInfoOrAbove()}：直接断言生产配置显式声明 AWS SDK 与
 *       Apache 传输层 logger 均 &gt;= INFO——加固的<b>声明源</b>。</li>
 *   <li>{@link #pinnedLevelBlocksRawThrowableFromAnyAppenderAtRuntime()}：从同一配置读出被钉级别、应用到活
 *       {@link LoggerContext}，用 {@link ListAppender} <b>捕获</b>日志事件，模拟 SDK abort 路径的
 *       {@code debug(Supplier, Throwable)} 调用，断言原始异常（金丝雀签名参数）在被钉级别下不入任何 appender；
 *       再以<b>强制 DEBUG 的正向对照</b>证明该测试具备鉴别力（泄露路径一旦复活即变红）——直接堵住「测试不捕获
 *       日志、增加泄密日志仍可保持绿色」的证据缺口。</li>
 *   <li>{@link #pinnedLevelBlocksApacheTransportHeaderLeakAtRuntime()}：同一手法针对 Apache 传输层——模拟
 *       {@code headers} logger 的 {@code debug("{} >> {}", id, Authorization 头)}，断言被钉级别下含 secret-id/
 *       签名金丝雀的请求头不入任何 appender，并以强制 DEBUG 正向对照内置鉴别力。</li>
 * </ul>
 * <b>为何不做朴素运行时 {@code isDebugEnabled()} 断言</b>：纯 JUnit（无 Spring 上下文）下 Logback 加载的是
 * {@code logback.xml}/{@code logback-test.xml}，<b>不会</b>加载 {@code logback-spring.xml}（后者由 Spring Boot 的
 * LogbackLoggingSystem 加载），故朴素运行时断言反映的是 Logback 缺省（root=DEBUG）而非本项目生产加固，属误导性
 * 测试。运行时测试因此<b>显式应用</b>从生产配置解析出的级别，而非依赖环境默认——两测试共用同一事实源，形成
 * 「声明 + 行为」闭环。
 *
 * <p>本加固<b>不依赖 root 级别</b>，故有意不断言 root——运维可将 root 调至 DEBUG 排查业务问题，
 * 只要下列 SDK logger 仍被钉住，加固即不失效。这正是显式钉级别相对"依赖 root 默认 INFO"的价值：
 * 一个 {@code logging.level.root=DEBUG} 环境变量即可绕过 root 默认，但绕不过显式声明的 logger 级别。
 */
class SdkLoggingHardeningContractTest {

    /**
     * 必须被显式钉为 &gt;= INFO 的 AWS SDK logger：
     * 命名空间级（纵深防御，SDK 其它 DEBUG/TRACE 路径同样可能输出请求 URI）
     * + 字节码实证的具体泄露路径（供未来维护者按类名搜索到加固意图）。
     */
    private static final String[] PINNED_SDK_LOGGERS = {
            "software.amazon.awssdk",
            "software.amazon.awssdk.core.ResponseInputStream"
    };

    /**
     * 必须被显式钉为 &gt;= INFO 的 Apache HttpClient5 传输层 logger（codex round-5 P1）：
     * AWS SDK v2 默认同步 HTTP 客户端 apache5-client 经这些 logger 在 DEBUG 下打印含 Authorization
     * （Credential=secret-id、Signature=）的请求头与完整线路内容，且不在 awssdk 命名空间下，故须独立钉级别。
     * 父命名空间 + 两个实证泄露子 logger（headers/wire）同列，便于按名搜索到加固意图。
     */
    private static final String[] PINNED_HTTP_TRANSPORT_LOGGERS = {
            "org.apache.hc.client5.http",
            "org.apache.hc.client5.http.headers",
            "org.apache.hc.client5.http.wire"
    };

    /** 生产日志配置；位于本模块 src/main/resources，天然在本模块测试 classpath 上。 */
    private static final String LOGBACK_CONFIG = "/logback-spring.xml";

    @Test
    @DisplayName("生产 logback 配置显式将 AWS SDK 与 Apache HttpClient5 传输层 logger 钉为 >= INFO，阻断凭据/签名/原始异常进入任何 appender")
    void productionLogbackPinsAwsSdkLoggersToInfoOrAbove() throws Exception {
        Document config = parseLogbackConfig();

        // 两条泄露面同一断言口径：AWS SDK 内部 debug(Supplier,Throwable) 与 Apache 传输层 header/wire debug。
        for (String loggerName : PINNED_SDK_LOGGERS) {
            assertPinnedAtLeastInfo(config, loggerName);
        }
        for (String loggerName : PINNED_HTTP_TRANSPORT_LOGGERS) {
            assertPinnedAtLeastInfo(config, loggerName);
        }
    }

    /** 断言指定 logger 在配置中显式声明、level 可识别、且级别 &gt;= INFO（缺失/不可识别/DEBUG/TRACE 均判失败）。 */
    private void assertPinnedAtLeastInfo(Document config, String loggerName) {
        Element logger = findLoggerElement(config, loggerName);

        assertThat(logger)
                .as("logback-spring.xml 必须显式声明 <logger name=\"%s\">：缺失时该 logger 继承 root，"
                        + "一旦 root 被调至 DEBUG，其 debug 调用就会输出可能含凭据/签名 URL 的内容（铁律 1 违规）",
                        loggerName)
                .isNotNull();

        String rawLevel = logger.getAttribute("level");
        assertThat(rawLevel)
                .as("<logger name=\"%s\"> 必须显式声明 level 属性（缺失等价于继承 root，加固失效）", loggerName)
                .isNotBlank();

        // 用两参重载并传 null 缺省值：级别串不可识别时返回 null 而非静默降级为 DEBUG。
        Level level = Level.toLevel(rawLevel.trim(), null);
        assertThat(level)
                .as("<logger name=\"%s\"> 的 level=\"%s\" 不是可识别的 Logback 级别", loggerName, rawLevel)
                .isNotNull();
        assertThat(level.toInt())
                .as("<logger name=\"%s\"> 的级别必须 >= INFO 才能阻断 DEBUG 泄露（DEBUG/TRACE 会放行 debug 调用）",
                        loggerName)
                .isGreaterThanOrEqualTo(Level.INFO_INT);
    }

    @Test
    @DisplayName("运行时：被钉级别下 SDK debug(msg,原始异常) 不进任何 appender；强制 DEBUG 则泄露（正向对照证明鉴别力）")
    void pinnedLevelBlocksRawThrowableFromAnyAppenderAtRuntime() throws Exception {
        // 从生产配置解析被钉级别（与配置解析测试同一事实源），运行时显式应用，
        // 而非依赖纯 JUnit 环境的 Logback 缺省（缺省 root=DEBUG 会掩盖加固、令断言失真）。
        Level pinned = readPinnedLevelFromConfig("software.amazon.awssdk.core.ResponseInputStream");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger sdkLogger = context.getLogger("software.amazon.awssdk.core.ResponseInputStream");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        Level originalLevel = sdkLogger.getLevel();
        boolean originalAdditivity = sdkLogger.isAdditive();
        sdkLogger.setAdditive(false); // 不向 root appender 传播，避免测试期控制台/文件噪声
        sdkLogger.addAppender(appender);
        try {
            // 复现 IoUtils.closeQuietlyV2 在底层 close() 失败时的确切调用：debug(常量, 原始 Throwable)——
            // 泄露载体是 Throwable 的消息/链（可能含签名 URL）。logback 的 debug(String,Throwable) 与
            // debug(Supplier,Throwable) 同样先判 isDebugEnabled() 再建事件，故用 String 重载等价复现该门控与
            // Throwable 捕获语义（且跨 SLF4J 版本可移植）。
            String canary = "X-Amz-Signature=CANARY-9f8e7d6c5b4a3210";
            Throwable rawCloseFailure = new IOException(
                    "Failed to close stream for https://bucket.cos.ap-guangzhou.myqcloud.com/o.png?" + canary);

            // (1) 生产被钉级别（>= INFO）：isDebugEnabled()==false，事件根本不创建，金丝雀不入任何 appender。
            sdkLogger.setLevel(pinned);
            sdkLogger.debug("Ignore failure in closing the Closeable", rawCloseFailure);
            assertThat(sdkLogger.isDebugEnabled())
                    .as("被钉级别 %s 下 SDK logger 必须 isDebugEnabled()==false", pinned)
                    .isFalse();
            assertThat(appender.list)
                    .as("被钉级别下原始异常绝不能进入任何 appender（铁律 1）")
                    .isEmpty();

            // (2) 正向对照（鉴别力）：强制 DEBUG——同一调用立即把金丝雀泄入 appender，
            //     证明上面的"空捕获"并非测试恒定绿；若加固被移除/削弱，(1) 即变红。
            sdkLogger.setLevel(Level.DEBUG);
            sdkLogger.debug("Ignore failure in closing the Closeable", rawCloseFailure);
            assertThat(appender.list)
                    .as("正向对照：DEBUG 下同一调用必须被捕获，否则测试无鉴别力")
                    .hasSize(1);
            ILoggingEvent leaked = appender.list.get(0);
            assertThat(leaked.getThrowableProxy())
                    .as("DEBUG 下原始 Throwable 确会随事件进入 appender")
                    .isNotNull();
            assertThat(leaked.getThrowableProxy().getMessage())
                    .as("正向对照须证明泄露载体确为原始异常消息（含金丝雀签名参数）")
                    .contains("CANARY-9f8e7d6c5b4a3210");
        } finally {
            sdkLogger.detachAppender(appender);
            appender.stop();
            sdkLogger.setLevel(originalLevel);       // 还原，避免污染同 JVM 其它测试
            sdkLogger.setAdditive(originalAdditivity);
        }
    }

    @Test
    @DisplayName("运行时：被钉级别下 Apache HttpClient5 header logger 的 debug(Authorization 头) 不进任何 appender；强制 DEBUG 则泄露 secret-id/签名（正向对照证明鉴别力）")
    void pinnedLevelBlocksApacheTransportHeaderLeakAtRuntime() throws Exception {
        // round-5 P1：AWS SDK v2 默认同步传输层 apache5-client 的 header logger 在 DEBUG 下打印含 Authorization 的请求头。
        // 从生产配置解析被钉级别、运行时显式应用（而非依赖纯 JUnit 环境缺省），与配置解析测试共用同一事实源。
        Level pinned = readPinnedLevelFromConfig("org.apache.hc.client5.http.headers");

        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger headerLogger = context.getLogger("org.apache.hc.client5.http.headers");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();

        Level originalLevel = headerLogger.getLevel();
        boolean originalAdditivity = headerLogger.isAdditive();
        headerLogger.setAdditive(false);
        headerLogger.addAppender(appender);
        try {
            // 复现 DefaultManagedHttpClientConnection.onRequestSubmitted 的 debug("{} >> {}", id, header)：
            // Authorization 头由 AbstractAws4Signer.buildAuthorizationHeader 拼接 Credential=<secret-id>、Signature=<sig>。
            // 用虚构金丝雀凭据（绝非真实密钥），验证其在被钉级别下不入任何 appender。
            String secretIdCanary = "AKIDSECRETIDCANARY1234567890";
            String signatureCanary = "SIGCANARY1a2b3c4d5e6f7a8b9c0d";
            String authorizationHeader = "Authorization: AWS4-HMAC-SHA256 Credential=" + secretIdCanary
                    + "/20260911/ap-guangzhou/cos/aws4_request, SignedHeaders=host, Signature=" + signatureCanary;

            // (1) 生产被钉级别（>= INFO）：isDebugEnabled()==false，事件根本不创建，凭据不入任何 appender。
            headerLogger.setLevel(pinned);
            headerLogger.debug("{} >> {}", "conn-1", authorizationHeader);
            assertThat(headerLogger.isDebugEnabled())
                    .as("被钉级别 %s 下 Apache header logger 必须 isDebugEnabled()==false", pinned)
                    .isFalse();
            assertThat(appender.list)
                    .as("被钉级别下 Authorization 头（含 secret-id/签名）绝不能进入任何 appender（铁律 1）")
                    .isEmpty();

            // (2) 正向对照（鉴别力）：强制 DEBUG（等价 root=DEBUG 未钉时的继承结果），同一调用立即泄露凭据。
            headerLogger.setLevel(Level.DEBUG);
            headerLogger.debug("{} >> {}", "conn-1", authorizationHeader);
            assertThat(appender.list)
                    .as("正向对照：DEBUG 下同一调用必须被捕获，否则测试无鉴别力")
                    .hasSize(1);
            assertThat(appender.list.get(0).getFormattedMessage())
                    .as("正向对照须证明泄露载体确为含 secret-id 与签名的 Authorization 头")
                    .contains(secretIdCanary)
                    .contains(signatureCanary);
        } finally {
            headerLogger.detachAppender(appender);
            appender.stop();
            headerLogger.setLevel(originalLevel);
            headerLogger.setAdditive(originalAdditivity);
        }
    }

    /**
     * 从生产 {@code logback-spring.xml} 解析指定 logger 被钉的级别；运行时测试与配置解析测试共用同一事实源。
     */
    private Level readPinnedLevelFromConfig(String loggerName) throws Exception {
        Element logger = findLoggerElement(parseLogbackConfig(), loggerName);
        assertThat(logger)
                .as("logback-spring.xml 必须声明 <logger name=\"%s\">", loggerName)
                .isNotNull();
        Level level = Level.toLevel(logger.getAttribute("level").trim(), null);
        assertThat(level)
                .as("<logger name=\"%s\"> 的 level 必须是可识别的 Logback 级别", loggerName)
                .isNotNull();
        return level;
    }

    /**
     * 从测试 classpath 读取并解析生产日志配置。
     *
     * <p>禁用 DOCTYPE 与外部实体：即使这是仓库内的可信文件，也不为解析路径引入 XXE 面。
     */
    private Document parseLogbackConfig() throws Exception {
        InputStream resource = getClass().getResourceAsStream(LOGBACK_CONFIG);
        assertThat(resource)
                .as("测试 classpath 上必须存在 %s（yudao-server/src/main/resources）", LOGBACK_CONFIG)
                .isNotNull();

        try (InputStream in = resource) {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(in);
        }
    }

    /** 按 name 属性精确匹配 {@code <logger>} 元素；未声明时返回 null（由断言给出可读原因）。 */
    private Element findLoggerElement(Document config, String loggerName) {
        NodeList loggers = config.getDocumentElement().getElementsByTagName("logger");
        for (int i = 0; i < loggers.getLength(); i++) {
            Element element = (Element) loggers.item(i);
            if (loggerName.equals(element.getAttribute("name"))) {
                return element;
            }
        }
        return null;
    }
}
