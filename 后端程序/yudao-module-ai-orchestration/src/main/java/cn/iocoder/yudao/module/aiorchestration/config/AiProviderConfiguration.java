package cn.iocoder.yudao.module.aiorchestration.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * AI Provider 配置面装配（D-11 / EXT-05，2026-09-12 冻结）。
 *
 * <p>作用：令 {@link AiProviderProperties} 在应用启动即绑定为 Spring Bean，使 {@code zhongshu.ai.provider.*}
 * 成为受管、可被 Actuator/环境观测的配置面。仿 {@code SmsConfiguration} / {@code CodegenConfiguration}
 * 的独立注册范式（{@code @Configuration(proxyBeanMethods = false)} + {@code @EnableConfigurationProperties}）。
 *
 * <p><b>边界</b>：本配置类<b>不装配任何 HTTP 客户端、不发起任何出站调用</b>。本仓库 ai-orchestration 无出站生成引擎，
 * 真实 Provider 调用由外部 AI Runtime（架构 P4D）承担；当前 {@link AiProviderProperties} 无 Java 消费者，
 * 仅为配置面前向声明（详见该类 javadoc）。待 P4D Runtime 落地后由其消费，本类无需改动。
 *
 * @author 众墅之家设计平台（D-11 / EXT-05，2026-09-12 冻结）
 * @see AiProviderProperties
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProviderProperties.class)
public class AiProviderConfiguration {
}
