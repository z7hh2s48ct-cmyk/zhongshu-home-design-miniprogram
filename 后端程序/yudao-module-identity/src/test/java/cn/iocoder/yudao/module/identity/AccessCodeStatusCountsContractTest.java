package cn.iocoder.yudao.module.identity;

import cn.iocoder.yudao.module.identity.accesscode.AccessCodeService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 授权码四态计数合同测试（真实 PostgreSQL）：
 * UNUSED/BOUND/EXPIRED/DISABLED 互斥、合计等于全量、EXPIRED 由 ACTIVE 且过期时间推导
 * （statusCounts 只做表上 SQL 统计，cipher/deliveryPort 不参与，传 null 即可）。
 * 断言用插入前后增量，避免对执行顺序与共享容器内既有数据的依赖。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccessCodeStatusCountsContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private JdbcTemplate jdbc;
    private AccessCodeService accessCodeService;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration/identity").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        accessCodeService = new AccessCodeService(dataSource,
                new DataSourceTransactionManager(dataSource), null, null);
    }

    private void insertCode(String hash, String status, String expiresAtSql) {
        jdbc.update("INSERT INTO design_access_code (id, batch_id, code_hash, pepper_version, code_mask, "
                        + "status, expires_at) VALUES (?, ?, ?, 'v1', ?, ?, "
                        + (expiresAtSql == null ? "NULL" : expiresAtSql) + ")",
                IdWorker.getId(), 1L, hash, "ZSZJ-****-" + hash.substring(0, 4), status);
    }

    @Test
    void 四态互斥且合计等于全量_过期由ACTIVE推导() {
        Map<String, Long> before = accessCodeService.statusCounts();
        insertCode("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "ACTIVE", null);
        insertCode("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "ACTIVE", "now() - interval '1 hour'");
        insertCode("cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", "CONSUMED", null);
        insertCode("dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd", "DISABLED", null);

        Map<String, Long> after = accessCodeService.statusCounts();

        assertThat(after.getOrDefault("UNUSED", 0L) - before.getOrDefault("UNUSED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("EXPIRED", 0L) - before.getOrDefault("EXPIRED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("BOUND", 0L) - before.getOrDefault("BOUND", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("DISABLED", 0L) - before.getOrDefault("DISABLED", 0L)).isEqualTo(1L);
        // 四类互斥：合计恒等于全量
        long total = after.values().stream().mapToLong(Long::longValue).sum();
        Long all = jdbc.queryForObject("SELECT count(*) FROM design_access_code WHERE deleted = FALSE", Long.class);
        assertThat(total).isEqualTo(all);
    }

    @Test
    void 未过期但设置了有效期的码仍计为未使用() {
        Map<String, Long> before = accessCodeService.statusCounts();
        insertCode("eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee", "ACTIVE", "now() + interval '1 hour'");
        insertCode("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff", "ACTIVE", "now() - interval '2 hours'");

        Map<String, Long> after = accessCodeService.statusCounts();

        assertThat(after.getOrDefault("UNUSED", 0L) - before.getOrDefault("UNUSED", 0L)).isEqualTo(1L);
        assertThat(after.getOrDefault("EXPIRED", 0L) - before.getOrDefault("EXPIRED", 0L)).isEqualTo(1L);
    }
}
