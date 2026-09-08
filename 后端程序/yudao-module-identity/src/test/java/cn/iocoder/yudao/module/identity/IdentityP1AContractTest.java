package cn.iocoder.yudao.module.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.ClassUtils;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1A 合同测试：三分面路由约定、模块零交叉依赖、业务对象 allowedActions 合同
 */
class IdentityP1AContractTest {

    private static final String BASE_PACKAGE = "cn.iocoder.yudao.module.identity";
    private static final Set<String> BUSINESS_MODULES = Set.of(
            "yudao-module-identity", "yudao-module-design", "yudao-module-commerce", "yudao-module-ai-orchestration");

    private List<Class<?>> scanControllers() throws Exception {
        ClassPathScanningCandidateComponentProvider provider =
                new ClassPathScanningCandidateComponentProvider(false);
        provider.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        for (var bd : provider.findCandidateComponents(BASE_PACKAGE)) {
            classes.add(ClassUtils.forName(bd.getBeanClassName(), getClass().getClassLoader()));
        }
        return classes;
    }

    @Test
    void controllersFollowThreeFacetRouteContract() throws Exception {
        List<Class<?>> controllers = scanControllers();
        assertThat(controllers).isNotEmpty();
        for (Class<?> controller : controllers) {
            String pkg = controller.getPackageName();
            RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            assertThat(mapping).as("%s 缺少类级 @RequestMapping", controller.getName()).isNotNull();
            assertThat(mapping.value()).as("%s 必须声明类级路径", controller.getName()).isNotEmpty();
            String path = mapping.value()[0];
            if (pkg.endsWith(".controller.app")) {
                assertThat(path.equals("/design/v1") || path.startsWith("/design/v1/"))
                        .as("app 分面路径必须位于 /design/v1 下：%s", path).isTrue();
            } else if (pkg.endsWith(".controller.admin")) {
                assertThat(path.equals("/design/v1") || path.startsWith("/design/v1/"))
                        .as("admin 分面路径必须位于 /design/v1 下：%s", path).isTrue();
            } else if (pkg.endsWith(".controller.internal")) {
                assertThat(path.equals("/internal-api/design/v1") || path.startsWith("/internal-api/design/v1/"))
                        .as("internal 分面路径必须位于 /internal-api/design/v1 下：%s", path).isTrue();
            } else {
                throw new AssertionError("控制器必须在 controller.admin/app/internal 包下：" + controller.getName());
            }
        }
    }

    @Test
    void pomMustNotDependOnOtherBusinessModules() throws Exception {
        Document pom;
        try (InputStream in = java.nio.file.Files.newInputStream(java.nio.file.Path.of("pom.xml"))) {
            pom = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
        }
        String ownArtifactId = pom.getElementsByTagName("artifactId").item(1).getTextContent().trim();
        assertThat(BUSINESS_MODULES).contains(ownArtifactId);
        NodeList deps = pom.getElementsByTagName("dependency");
        for (int i = 0; i < deps.getLength(); i++) {
            NodeList children = deps.item(i).getChildNodes();
            String artifactId = null;
            String scope = "compile";
            for (int j = 0; j < children.getLength(); j++) {
                if ("artifactId".equals(children.item(j).getNodeName())) {
                    artifactId = children.item(j).getTextContent().trim();
                }
                if ("scope".equals(children.item(j).getNodeName())) {
                    scope = children.item(j).getTextContent().trim();
                }
            }
            if ("test".equals(scope)) {
                // 测试期业务模块依赖允许（如 design 测试驱动真实 ai-orchestration 链）；主代码仍零业务依赖
                continue;
            }
            assertThat(artifactId)
                    .as("业务模块之间禁止 Maven 依赖（跨模块只经 Application Service/领域事件/Port）：%s -> %s",
                            ownArtifactId, artifactId)
                    .isNotIn(BUSINESS_MODULES.stream().filter(m -> !m.equals(ownArtifactId)).toList());
        }
    }

    @Test
    void accessGrantVoCarriesAllowedActionsContract() {
        List<String> fields = java.util.Arrays.stream(
                        cn.iocoder.yudao.module.identity.controller.app.vo.AppAccessGrantRespVO.class.getDeclaredFields())
                .map(Field::getName).toList();
        assertThat(fields).contains("status", "allowedActions");
    }


    @Test
    void adminEndpointsMustDeclarePreAuthorize() throws Exception {
        for (Class<?> controller : scanControllers()) {
            if (!controller.getPackageName().endsWith(".controller.admin")) {
                continue;
            }
            for (java.lang.reflect.Method method : controller.getDeclaredMethods()) {
                boolean isHandler = java.util.Arrays.stream(method.getAnnotations())
                        .anyMatch(a -> a.annotationType().getSimpleName().endsWith("Mapping"));
                if (isHandler) {
                    assertThat(AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class))
                            .as("admin 端点必须声明 @PreAuthorize：%s#%s", controller.getSimpleName(), method.getName())
                            .isNotNull();
                }
            }
        }
    }
}
