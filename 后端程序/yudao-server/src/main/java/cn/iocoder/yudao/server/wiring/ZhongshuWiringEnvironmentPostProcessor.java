package cn.iocoder.yudao.server.wiring;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.Arrays;
import java.util.Collection;

/**
 * 启动期装配守卫（T13-01）。
 *
 * <p>在 Spring 上下文创建之前校验「真实/Stub 实现选择」配置：任一端口 provider 缺失/非法，
 * 或生产 profile 下选择了开发替身（stub/local），立即快速失败并给出明确原因。
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
    }

    @Override
    public int getOrder() {
        // 在 ConfigDataEnvironmentPostProcessor 加载各 profile 的 application-*.yaml 之后执行，
        // 确保 activeProfiles 与 provider 配置均已就绪。
        return Ordered.LOWEST_PRECEDENCE;
    }
}
