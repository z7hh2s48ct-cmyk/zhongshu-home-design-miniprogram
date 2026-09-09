package cn.iocoder.yudao.framework.test.core.util;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 轻量 HTTP 假服务器（T13-03「无真实消费测试基线」）。
 *
 * <p>用途：为将发起 HTTP 出站调用的真实适配器提供隔离测试替身——
 * B1 微信 {@code code2session}、B4 微信支付 API（预下单/查单/退款）等的测试
 * 指向本地回环假服务器，而非真实微信/腾讯端点，从而「不产生任何真实消费」。
 *
 * <p>基于 JDK 自带 {@code com.sun.net.httpserver.HttpServer}（{@code jdk.httpserver} 模块），
 * 不引入任何新第三方依赖。在临时端口启动，随 {@link #close()} 释放；配合 try-with-resources 使用。
 *
 * <p>与「SDK mock」（Mockito，见 {@code mockito-inline}）、「隔离 PG」（Testcontainers）共同构成
 * T13-03 三类无真实消费测试手段。详见《T13-03 无真实消费测试基线与手工验收清单》。
 *
 * <pre>{@code
 * try (FakeHttpServer fake = FakeHttpServer.start()) {
 *     fake.stubGet("/sns/jscode2session", 200, "application/json", "{\"openid\":\"o1\"}");
 *     // 将适配器的 baseUrl 指向 fake.baseUrl() 后发起调用并断言 fake.lastRequest()
 * }
 * }</pre>
 *
 * @author 众墅之家设计平台（T13-03）
 */
public final class FakeHttpServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;
    /** 桩响应：key = "METHOD PATH"（PATH 不含查询串），value = 固定响应。 */
    private final Map<String, Stub> stubs = new ConcurrentHashMap<>();
    /** 按到达顺序记录的请求，供测试断言。 */
    private final List<RecordedRequest> recorded = new CopyOnWriteArrayList<>();

    private FakeHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /**
     * 在回环地址（127.0.0.1）的临时空闲端口启动假服务器。
     *
     * @return 已启动的假服务器实例
     * @throws IOException 端口绑定失败
     */
    public static FakeHttpServer start() throws IOException {
        // 显式绑定 IPv4 回环 127.0.0.1，而非 InetAddress.getLoopbackAddress()——后者在双栈主机可能返回 IPv6 ::1，
        // 直接拼接成 URL 需方括号（http://[::1]:port）且与本类「绑定 127.0.0.1」契约及自测断言不符。
        InetAddress loopbackIpv4 = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        HttpServer server = HttpServer.create(new InetSocketAddress(loopbackIpv4, 0), 0);
        ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "fake-http-server");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);
        FakeHttpServer fake = new FakeHttpServer(server, executor);
        // 单一根上下文捕获全部路径，由 handler 按 method+path 查桩
        server.createContext("/", fake::handle);
        server.start();
        return fake;
    }

    /** 假服务器基址，形如 {@code http://127.0.0.1:PORT}；供适配器拼接请求 URL。 */
    public String baseUrl() {
        InetSocketAddress address = server.getAddress();
        return "http://" + address.getHostString() + ":" + address.getPort();
    }

    /** 实际绑定的临时端口。 */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * 注册固定响应，按 {@code method + path} 精确匹配（忽略查询串）。重复注册同一键以最后一次为准。
     *
     * @param method      HTTP 方法（大小写不敏感）
     * @param path        请求路径（不含查询串），如 {@code /sns/jscode2session}
     * @param status      响应状态码
     * @param contentType 响应 {@code Content-Type}，为 {@code null} 时不设置该响应头
     * @param body        响应体（UTF-8），为 {@code null} 或空串时以无响应体发送
     * @return this（支持链式调用）
     */
    public FakeHttpServer stub(String method, String path, int status, String contentType, String body) {
        byte[] bytes = (body == null || body.isEmpty()) ? null : body.getBytes(StandardCharsets.UTF_8);
        stubs.put(key(method, path), new Stub(status, contentType, bytes));
        return this;
    }

    /** {@link #stub} 的 GET 便捷方法。 */
    public FakeHttpServer stubGet(String path, int status, String contentType, String body) {
        return stub("GET", path, status, contentType, body);
    }

    /** {@link #stub} 的 POST 便捷方法。 */
    public FakeHttpServer stubPost(String path, int status, String contentType, String body) {
        return stub("POST", path, status, contentType, body);
    }

    /** 已记录请求的只读快照（按到达顺序）。 */
    public List<RecordedRequest> requests() {
        return Collections.unmodifiableList(new ArrayList<>(recorded));
    }

    /**
     * 最近一次到达的请求。
     *
     * @throws IllegalStateException 尚未收到任何请求
     */
    public RecordedRequest lastRequest() {
        if (recorded.isEmpty()) {
            throw new IllegalStateException("假服务器尚未收到任何请求");
        }
        return recorded.get(recorded.size() - 1);
    }

    /** 清空全部桩与已记录请求，便于同一实例复用多个用例。 */
    public void reset() {
        stubs.clear();
        recorded.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            String query = exchange.getRequestURI().getQuery();
            Map<String, List<String>> headers = copyHeaders(exchange.getRequestHeaders());
            // 必须先读完请求体，否则某些客户端会因连接未消费而阻塞
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            recorded.add(new RecordedRequest(method, path, query, headers, body));

            Stub stub = stubs.get(key(method, path));
            int status = (stub == null) ? 404 : stub.status();
            byte[] responseBody = (stub == null || stub.body() == null) ? new byte[0] : stub.body();
            if (stub != null && stub.contentType() != null) {
                exchange.getResponseHeaders().set("Content-Type", stub.contentType());
            }
            if (responseBody.length == 0) {
                // responseLength = -1 表示无响应体（对 204/404 空体均适用）
                exchange.sendResponseHeaders(status, -1);
            } else {
                exchange.sendResponseHeaders(status, responseBody.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(responseBody);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static Map<String, List<String>> copyHeaders(Headers headers) {
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        // List.copyOf 使每个头值列表不可变，配合外层 unmodifiableMap，令快照彻底只读（调用方无法回改服务端存储）。
        headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(copy);
    }

    private static String key(String method, String path) {
        return method.toUpperCase(Locale.ROOT) + " " + path;
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private record Stub(int status, String contentType, byte[] body) {
    }

    /**
     * 一次被假服务器记录的请求，供测试断言。
     *
     * @param method  HTTP 方法
     * @param path    请求路径（不含查询串）
     * @param query   查询串，无则为 {@code null}
     * @param headers 请求头（键大小写不敏感的只读副本）
     * @param body    请求体（UTF-8 解码），无则为空串
     */
    public record RecordedRequest(String method, String path, String query,
                                  Map<String, List<String>> headers, String body) {
    }

}
