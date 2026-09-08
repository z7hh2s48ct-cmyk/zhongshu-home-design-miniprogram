package cn.iocoder.yudao;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Controller 层契约（审查遗留）：防占位端点回退。
 *
 * 规则：所有 @RestController 必须注入至少一个非框架依赖（@Resource/@Autowired 字段或
 * 构造器参数中的业务 Bean）——P1A 占位骨架的控制器全部无依赖，接完线必有依赖。
 * 新增"看似接了线实际空壳"的控制器会被本测试拦下。
 *
 * 底座 web starter（CommonResult 等类型来自 yudao-framework）不在扫描包内，不受影响。
 */
class ControllerWiringContractTest {

    private static final String BASE_PACKAGE = "cn.iocoder.yudao.module";

    private static final List<String> BUSINESS_MODULES = List.of(
            "yudao-module-identity", "yudao-module-design", "yudao-module-commerce",
            "yudao-module-ai-orchestration");

    private record ControllerInfo(Class<?> clazz, String module) {
    }

    private List<ControllerInfo> scanControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<ControllerInfo> result = new ArrayList<>();
        for (String module : BUSINESS_MODULES) {
            String pkg = "cn.iocoder.yudao.module." + module.replace("yudao-module-", "");
            for (var bd : provider.findCandidateComponents(pkg)) {
                result.add(new ControllerInfo(
                        ClassUtils.forName(bd.getBeanClassName(), getClass().getClassLoader()), module));
            }
        }
        return result;
    }

    private boolean hasBusinessDependency(Class<?> controller) {
        // 字段注入：@Resource / @Autowired
        for (Field field : controller.getDeclaredFields()) {
            if (field.isAnnotationPresent(jakarta.annotation.Resource.class)
                    || field.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)
                    || field.isAnnotationPresent(
                            org.springframework.beans.factory.annotation.Value.class)) {
                return true;
            }
        }
        // 构造器注入：有非默认构造器且参数含业务/端口类型
        for (var constructor : controller.getDeclaredConstructors()) {
            if (constructor.getParameterCount() > 0) {
                return true;
            }
        }
        return false;
    }

    @Test
    void everyControllerIsWiredToBusinessLogic() throws Exception {
        List<ControllerInfo> controllers = scanControllers();
        assertThat(controllers).isNotEmpty();
        List<String> unwired = new ArrayList<>();
        for (ControllerInfo info : controllers) {
            if (!hasBusinessDependency(info.clazz())) {
                unwired.add(info.clazz().getName());
            }
        }
        assertThat(unwired)
                .as("以下 Controller 仍是无依赖的占位空壳（P1A 骨架态），必须接线后才能交付：%s", unwired)
                .isEmpty();
    }

    @Test
    void appControllersDeclarePermitAllOrStayUnderBaseAuth() throws Exception {
        // App 端点要么 @PermitAll（自有会话校验），要么依赖底座登录态——不允许两者皆无的"悬空"端点
        // （悬空 = 部署态 401）。这里检查所有 app 包 controller 类或方法级至少有 @PermitAll 标注，
        // 否则必须有类级说明（本断言以 @PermitAll 出现为准，避免回归 P2A/P3A 的 401 事故）。
        List<String> dangling = new ArrayList<>();
        for (ControllerInfo info : scanControllers()) {
            if (!info.clazz().getPackageName().endsWith(".controller.app")) {
                continue;
            }
            boolean classLevel = info.clazz().isAnnotationPresent(jakarta.annotation.security.PermitAll.class);
            boolean methodLevel = false;
            for (Method method : info.clazz().getDeclaredMethods()) {
                if (method.isAnnotationPresent(jakarta.annotation.security.PermitAll.class)) {
                    methodLevel = true;
                    break;
                }
            }
            if (!classLevel && !methodLevel) {
                dangling.add(info.clazz().getName());
            }
        }
        assertThat(dangling)
                .as("以下 app Controller 无 @PermitAll 且自有会话不走底座过滤器，部署态将 401：%s", dangling)
                .isEmpty();
    }

}
