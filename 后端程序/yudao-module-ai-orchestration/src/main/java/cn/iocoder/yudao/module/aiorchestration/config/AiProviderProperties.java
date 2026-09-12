package cn.iocoder.yudao.module.aiorchestration.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI Provider 配置面（D-11 / EXT-05，2026-09-12 冻结）。
 *
 * <p><b>冻结决策（D-11）</b>：Provider = apilio.ai，OpenAI 兼容 {@code /v1/chat/completions}；
 * 默认模型 {@code gpt-5.6-sol}，采样温度 {@code 0.7}；base-url 形如 {@code https://api.apilio.ai/v1}。
 *
 * <p><b>⚠️ 本仓库当前无消费者（诚实标注）</b>：ai-orchestration 模块只承担 AI 任务的
 * 准入 / 租约与 fencing token / 结果事件 Inbox / 槽位结算（见 {@code AiJobInternalController} 的
 * 入站 HMAC 内部接口），<b>不含出站生成引擎</b>——真实的 Provider 调用（发起 chat/completions、
 * 拉取产物、回写结果事件）由外部 AI Runtime（架构 P4D）承担，Runtime 不在本仓库。
 * 因此本类目前只是「前向声明的配置面」：启动即绑定属性（见 {@link AiProviderConfiguration}），
 * 但不装配任何 HTTP 客户端、不被任何业务代码读取。待 P4D Runtime 落地后，由 Runtime 或届时新增的
 * Provider 适配器消费这四个字段，无需再改配置键名。
 *
 * <p><b>校验策略</b>：遵 {@code RealServiceWiringPolicy} T12「只登记已被消费的键」纪律，
 * 因当前无消费者，{@code api-key} <b>暂不纳入</b> {@code REAL_MODE_REQUIREMENTS} 强制启动守卫
 * （无消费者时纳入会让生产为一个用不上的键启动失败）。密钥安全由既有兜底保障：
 * Actuator env/configprops 端点 {@code show-values=NEVER}（见 application-pg.yaml 文件末），
 * {@code api-key} 只经部署环境变量 {@code ZS_AI_API_KEY} 带外注入，绝不写入源码/配置文件/提交。
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
     * 消费点：待 P4D 外部 Runtime / Provider 适配器构造 chat/completions 请求 URL 时使用（本仓库当前无消费者）。
     */
    private String baseUrl;

    /**
     * Provider API 密钥（Bearer）。
     * 环境变量：{@code ZS_AI_API_KEY}；<b>属机密</b>，只经部署环境带外注入，绝不写入源码/配置文件/提交，
     * 错误消息与日志绝不回显其值。
     * 消费点：待 P4D 外部 Runtime / Provider 适配器发起请求时置入 {@code Authorization} 头（本仓库当前无消费者）。
     */
    private String apiKey;

    /**
     * 生成模型标识。
     * 冻结默认值：{@code gpt-5.6-sol}（apilio.ai）。
     * 环境变量：{@code ZS_AI_MODEL}。非机密。
     * 消费点：待 P4D 外部 Runtime / Provider 适配器置入请求体 {@code model} 字段（本仓库当前无消费者）。
     */
    private String model;

    /**
     * 采样温度（0.0～2.0）。
     * 冻结默认值：{@code 0.7}。
     * 环境变量：{@code ZS_AI_TEMPERATURE}。非机密。
     * 消费点：待 P4D 外部 Runtime / Provider 适配器置入请求体 {@code temperature} 字段（本仓库当前无消费者）。
     * 采用包装类型 {@link Double}：未配置时为 {@code null}，与「显式配 0」区分。
     */
    private Double temperature;
}
