package cn.iocoder.yudao.module.aiorchestration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Provider 配置面（D-11 / EXT-05，2026-09-12 冻结）。
 *
 * <p><b>冻结决策（D-11）</b>：Provider = apilio.ai，OpenAI 兼容 {@code /v1/chat/completions}；
 * 默认模型 {@code gpt-5.6-sol}，采样温度 {@code 0.7}；base-url 形如 {@code https://api.apilio.ai/v1}。
 *
 * <p>后端仅保留属性绑定，不装配 Provider 客户端。独立进程的实现位于仓库 ai-runtime/，
 * 由其直接读取部署环境，不会自动消费这个 Java Bean。Provider 密钥只需注入 Runtime，
 * 后端的此项可留空；Runtime 自身对密钥、图片模型和每日请求额度执行启动检查。
 *
 * @author 众墅之家设计平台（D-11 / EXT-05，2026-09-12 冻结）
 * @see AiProviderConfiguration
 * @see cn.iocoder.yudao.server.wiring.RealServiceWiringPolicy
 */
@Data
@ConfigurationProperties(prefix = "zhongshu.ai.provider")
public class AiProviderProperties {

    /**
     * Provider 服务基址（OpenAI 兼容）。
     * 冻结默认值：{@code https://api.apilio.ai/v1}（apilio.ai）。
     * 环境变量：{@code ZS_AI_BASE_URL}。非机密。
     * 消费点：独立 ai-runtime 进程构造 chat/completions 请求 URL 时使用（直接读取环境变量，不读取本 Bean）。
     */
    private String baseUrl;

    /**
     * Provider API 密钥（Bearer）。
     * 环境变量：{@code ZS_AI_API_KEY}；<b>属机密</b>，只经部署环境带外注入，绝不写入源码/配置文件/提交，
     * 错误消息与日志绝不回显其值。
     * 消费点：独立 ai-runtime 进程发起请求时置入 {@code Authorization} 头（直接读取环境变量，不读取本 Bean）。
     */
    private String apiKey;

    /**
     * 生成模型标识。
     * 冻结默认值：{@code gpt-5.6-sol}（apilio.ai）。
     * 环境变量：{@code ZS_AI_MODEL}。非机密。
     * 消费点：独立 ai-runtime 进程置入请求体 {@code model} 字段（直接读取环境变量，不读取本 Bean）。
     */
    private String model;

    /**
     * 采样温度（0.0～2.0）。
     * 冻结默认值：{@code 0.7}。
     * 环境变量：{@code ZS_AI_TEMPERATURE}。非机密。
     * 消费点：独立 ai-runtime 进程置入请求体 {@code temperature} 字段（直接读取环境变量，不读取本 Bean）。
     * 采用包装类型 {@link Double}：未配置时为 {@code null}，与「显式配 0」区分。
     */
    private Double temperature;
}
