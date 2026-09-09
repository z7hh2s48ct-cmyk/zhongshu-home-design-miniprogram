package cn.iocoder.yudao;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1B 配置守卫：pg profile 必须显式给出 Flyway 独立连接与 dynamic master 覆盖。
 *
 * 背景：底座数据源由 dynamic-datasource 接管（spring.datasource.dynamic.datasource.master.*），
 * 普通 spring.datasource.* 不会生效；Flyway 不给 spring.flyway.url 会回退到路由数据源（=master），
 * 造成对 MySQL 执行 PG 方言迁移的事故。本测试锁死这两类键，防止回归。
 */
class ZhongshuPgProfileConfigContractTest {

    @SuppressWarnings("unchecked")
    private Map<String, Object> loadPgProfile() throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("application-pg.yaml")) {
            assertThat(in).isNotNull();
            return new Yaml().load(in);
        }
    }

    @Test
    void pgProfileMustProvideFlywayDedicatedDatasource() throws Exception {
        Map<String, Object> spring = (Map<String, Object>) loadPgProfile().get("spring");
        assertThat(spring).as("pg profile 必须包含 spring 配置块").isNotNull();
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
        assertThat(flyway).isNotNull();
        assertThat((String) flyway.get("url")).contains("jdbc:postgresql://");
        assertThat((String) flyway.get("user")).isNotBlank();
        assertThat(flyway.get("enabled")).isEqualTo(true);
        // 段位命名（platform=0xx / identity=1xx / ...）下，后补低段位迁移必然全局乱序，必须允许
        assertThat(flyway.get("out-of-order")).isEqualTo(true);
        // 底座 dump 先行建表（schema 非空）后 Flyway 首次启动，必须允许建基线
        assertThat(flyway.get("baseline-on-migrate")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pgProfileMustOverrideDynamicDatasourceMaster() throws Exception {
        Map<String, Object> root = (Map<String, Object>) loadPgProfile().get("spring");
        Map<String, Object> master = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) root.get("datasource")).get("dynamic")).get("datasource");
        Map<String, Object> masterDs = (Map<String, Object>) master.get("master");
        assertThat((String) masterDs.get("url")).as("必须覆盖 dynamic master 到 PostgreSQL").contains("jdbc:postgresql://");
        assertThat((String) masterDs.get("driver-class-name")).isEqualTo("org.postgresql.Driver");
    }

    /**
     * 安全守卫：pg 是生产使用的数据库 profile，一旦在此写入密钥默认值，
     * 「缺失即快速失败」的合同就会退化为「静默使用已提交进 Git 的公开值」。
     * 开发占位值只允许放在 zsdev profile。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pgProfileMustNotCarrySecretDefaults() throws Exception {
        Map<String, Object> identity = (Map<String, Object>)
                ((Map<String, Object>) loadPgProfile().get("zhongshu")).get("identity");
        for (String key : List.of("access-code-pepper", "access-code-artifact-key", "wechat-appid")) {
            String value = (String) identity.get(key);
            assertThat(value).as(key + " 必须存在并只做环境变量占位").isNotNull();
            assertThat(value)
                    .as(key + " 在 pg profile 不得有默认值（应形如 ${ENV:}），否则密钥缺失不再快速失败")
                    .matches("\\$\\{[A-Z_]+:}");
        }
    }

    /**
     * 安全守卫：种子数据会写入测试授权码并把明文打进日志，开发内容端点会绕开对象存储签名，
     * 两者在生产 profile 必须默认关闭（fail-safe 而非 fail-open）。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pgProfileMustDisableDevSwitchesByDefault() throws Exception {
        Map<String, Object> design = (Map<String, Object>)
                ((Map<String, Object>) loadPgProfile().get("zhongshu")).get("design");
        assertThat((String) design.get("seed-dev-data"))
                .as("生产 profile 的种子数据开关必须默认 false").isEqualTo("${ZS_SEED_DEV_DATA:false}");
        Map<String, Object> asset = (Map<String, Object>) design.get("asset");
        assertThat((String) asset.get("dev-content-endpoint"))
                .as("生产 profile 的开发内容端点必须默认 false")
                .isEqualTo("${ZS_DEV_CONTENT_ENDPOINT:false}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void flywayLocationsCoverAllModules() throws Exception {
        Map<String, Object> spring = (Map<String, Object>) loadPgProfile().get("spring");
        assertThat(spring).as("pg profile 必须包含 spring 配置块").isNotNull();
        Map<String, Object> flyway = (Map<String, Object>) spring.get("flyway");
        String locations = (String) flyway.get("locations");
        assertThat(locations).isNotBlank();
        for (String module : List.of("platform", "identity", "design", "commerce", "ai-orchestration")) {
            assertThat(locations).as("迁移 locations 必须包含 " + module).contains("classpath:db/migration/" + module);
        }
    }

    /**
     * 安全守卫（T13-01）：pg 是生产安全基线，绝不把任何端口 provider 默认成开发替身（stub/local），
     * 否则「生产禁止 Stub」合同会退化为静默装配替身。生产 provider 只允许留空（缺失即启动守卫快速失败）
     * 或显式真实值；开发默认值只放 zsdev profile。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pgProfileMustNotDefaultAnyPortToStubProvider() throws Exception {
        Map<String, Object> zhongshu = (Map<String, Object>) loadPgProfile().get("zhongshu");
        List<String> stubProviders = new ArrayList<>();
        collectStubProviders(zhongshu, "zhongshu", stubProviders);
        assertThat(stubProviders)
                .as("pg profile 不得把任何 provider 设为开发替身（stub/local）")
                .isEmpty();
    }

    /**
     * 安全守卫（T13-02）：pg 生产安全基线必须把 Actuator env/configprops 的值回显显式设为安全默认 NEVER，
     * 使「管理端不回显密钥」不依赖框架默认（Boot 3.5 默认 never）。本测试锁定该 profile 级默认值；
     * 更高优先级来源（命令行/环境变量）仍可覆盖，生产须配合 actuator 访问控制（T13-34），此处不承诺不可覆盖。
     */
    @Test
    @SuppressWarnings("unchecked")
    void pgProfileMustHardenActuatorSecretEcho() throws Exception {
        Map<String, Object> management = (Map<String, Object>) loadPgProfile().get("management");
        assertThat(management).as("pg profile 必须包含 management 配置块").isNotNull();
        Map<String, Object> endpoint = (Map<String, Object>) management.get("endpoint");
        assertThat(endpoint).as("management.endpoint 必须存在").isNotNull();
        for (String ep : List.of("env", "configprops")) {
            Map<String, Object> node = (Map<String, Object>) endpoint.get(ep);
            assertThat(node).as("management.endpoint." + ep + " 必须存在").isNotNull();
            assertThat(String.valueOf(node.get("show-values")))
                    .as("management.endpoint." + ep + ".show-values 必须为 NEVER（不回显密钥值）")
                    .isEqualToIgnoringCase("NEVER");
        }
    }

    private void collectStubProviders(Object node, String path, List<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = path + "." + key;
                Object value = e.getValue();
                if ("provider".equals(key) && value != null) {
                    String v = String.valueOf(value).trim();
                    if (v.equals("stub") || v.equals("local")) {
                        out.add(childPath + "=" + v);
                    }
                }
                collectStubProviders(value, childPath, out);
            }
        }
    }

}
