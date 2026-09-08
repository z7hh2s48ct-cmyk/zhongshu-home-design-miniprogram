package cn.iocoder.yudao;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
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

}
