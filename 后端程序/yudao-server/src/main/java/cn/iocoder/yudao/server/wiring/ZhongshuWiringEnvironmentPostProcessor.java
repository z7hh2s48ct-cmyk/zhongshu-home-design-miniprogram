package cn.iocoder.yudao.server.wiring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.Collection;

/**
 * 启动期装配守卫（T13-01），承载两重校验，均在此快速失败：
 *
 * <ol>
 *   <li>{@link RealServiceWiringPolicy}——「真实/Stub 实现选择」：任一端口 provider 缺失/非法，
 *       或生产 profile 下选择了开发替身（stub/local），立即抛错并给出明确原因；真实实现还须校验其密钥键已配置。</li>
 *   <li>{@link SensitiveLoggingPolicy}——「敏感传输层日志级别」（B2 T13-10/11 追加，铁律 1）：
 *       {@code logging.level.*} 若把 AWS SDK／Apache HttpClient5 传输层放开至 DEBUG/TRACE，会令凭据、
 *       签名 URL、含 {@code Authorization} 的请求头落盘，覆盖 {@code logback-spring.xml} 的显式钉级别。</li>
 * </ol>
 *
 * <p>经由 {@code META-INF/spring.factories} 注册为 {@link EnvironmentPostProcessor}，
 * 仅在真实 {@link SpringApplication} 启动时触发；不影响直接 {@code new} 适配器
 * 或使用 {@code AnnotationConfigApplicationContext} 切片的合同测试。
 */
public class ZhongshuWiringEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        // Spring 语义：active profiles 为空时回退到 default profiles（AbstractEnvironment#isProfileActive）。
        // 守卫须按「生效 profile」判定生产，否则 --spring.profiles.active= 配合 --spring.profiles.default=prod
        // 会绕过「生产禁 Stub」校验（codex 评审 [P1]）。
        String[] active = environment.getActiveProfiles();
        Collection<String> effectiveProfiles = active.length > 0
                ? Arrays.asList(active)
                : Arrays.asList(environment.getDefaultProfiles());
        RealServiceWiringPolicy.validate(effectiveProfiles, environment::getProperty);
        // 第二重守卫：敏感传输层日志级别。放在 provider 校验之后——provider/密钥问题优先级更高，
        // 且两者违例各自聚合后独立抛出，不会互相掩盖。
        // 注意此处只能查 Environment 属性，不能查 logger 的 isDebugEnabled()：本阶段日志系统尚未初始化
        //（EnvironmentPostProcessorApplicationListener.DEFAULT_ORDER=-2147483638 早于
        // LoggingApplicationListener.DEFAULT_ORDER=-2147483628），实证见 SensitiveLoggingPolicy 类 javadoc。
        SensitiveLoggingPolicy.validate(environment);
    }

    @Override
    public int getOrder() {
        // 在 ConfigDataEnvironmentPostProcessor 加载各 profile 的 application-*.yaml 之后执行，
        // 确保 activeProfiles、provider 配置与 logging.level.* 均已就绪（后两者均可能来自 yaml）。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
