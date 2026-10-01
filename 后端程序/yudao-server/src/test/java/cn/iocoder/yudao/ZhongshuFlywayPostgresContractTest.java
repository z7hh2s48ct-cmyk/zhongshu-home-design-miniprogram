package cn.iocoder.yudao;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P1B 合同测试：真实 PostgreSQL 上的迁移基线
 *
 * 覆盖：全新库升级、逐步升级（target version）、约束红灯（CHECK/UNIQUE）、
 * 逆向脚本回滚演练（restore-then-forward 可重复）、最小并发夹具。
 * 禁止 H2/SQLite 冒充：本测试只接受真实 PostgreSQL（Testcontainers postgres:17-alpine）。
 */
@Testcontainers
class ZhongshuFlywayPostgresContractTest {

    /** 全部 location 的迁移执行总数（新增迁移时同步更新；2026-10-01 实测 8：platform 4 + 其余 location 4，含 007 授权码导出权限/008 积分价格菜单） */
    private static final int MIGRATION_COUNT = 8;

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");


    private Flyway flyway() {
        return buildFlyway(null);
    }

    private Flyway buildFlyway(String targetVersion) {
        org.flywaydb.core.api.configuration.FluentConfiguration builder = Flyway.configure()
                .dataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())
                .locations("classpath:db/migration/platform")
                // 与 pg profile 一致：兼容“底座 dump 先建表、Flyway 后建基线”的引导顺序
                .baselineOnMigrate(true)
                .baselineVersion("20260905.000")
                .cleanDisabled(false);
        if (targetVersion != null) {
            builder.target(targetVersion);
        }
        return builder.load();
    }

    @BeforeEach
    void resetDatabase() {
        flyway().clean();
    }

    private boolean tableExists(String name) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)")) {
            ps.setString(1, "public." + name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1) != null;
            }
        }
    }

    private boolean indexExists(String name) throws SQLException {
        try (Connection c = newConnection();
             PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)")) {
            ps.setString(1, "public." + name);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1) != null;
            }
        }
    }

    private int historyCount() throws SQLException {
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success = TRUE")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private Connection newConnection() throws SQLException {
        return DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = newConnection();
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private void execSqlFile(String classpathLocation) throws Exception {
        String content;
        try (var in = getClass().getClassLoader().getResourceAsStream(classpathLocation)) {
            assertThat(in).as("找不到脚本 " + classpathLocation).isNotNull();
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // 按分号切分，但跳过 $$ 美元引用块（DO $$ ... $$; 内部的分号不是语句边界）
        java.util.List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inDollar = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (ch == '$' && i + 1 < content.length() && content.charAt(i + 1) == '$') {
                inDollar = !inDollar;
                current.append("$$");
                i++;
                continue;
            }
            if (ch == ';' && !inDollar) {
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        parts.add(current.toString());
        for (String part : parts) {
            String sql = part.replaceAll("--[^\\n]*", "").trim();
            if (!sql.isEmpty()) {
                exec(sql);
            }
        }
    }

    @Test
    void freshUpgradeAppliesAllMigrations() throws Exception {
        var result = flyway().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(MIGRATION_COUNT);
        assertThat(tableExists("zhongshu_baseline_probe")).isTrue();
        assertThat(indexExists("idx_zhongshu_baseline_probe_create_time")).isTrue();
        assertThat(historyCount()).isEqualTo(MIGRATION_COUNT);
        // RG2 export recovery schema is part of a fresh platform database.
        exec("INSERT INTO export_job(job_type,requester_type,requester_user_id,lease_token,lease_expires_at,attempts) "
                + "VALUES('POINT_LEDGER','ADMIN',1,'lease-fixture',now(),1)");
    }

    @Test
    void stepwiseUpgradeStopsAtTargetVersion() throws Exception {
        buildFlyway("20260905.001").migrate();
        assertThat(tableExists("zhongshu_baseline_probe")).isTrue();
        assertThat(indexExists("idx_zhongshu_baseline_probe_create_time")).isFalse();
        assertThat(historyCount()).isEqualTo(1); // target 停在 V001

        flyway().migrate();
        assertThat(indexExists("idx_zhongshu_baseline_probe_create_time")).isTrue();
        assertThat(historyCount()).isEqualTo(MIGRATION_COUNT);
    }

    @Test
    void constraintRedLights() throws Exception {
        flyway().migrate();
        assertThatThrownBy(() -> exec(
                "INSERT INTO zhongshu_baseline_probe (id, probe_name, points) VALUES (1, 'negative', -1)"))
                .as("点数非负 CHECK 必须拦截负值")
                .isInstanceOf(SQLException.class);
        exec("INSERT INTO zhongshu_baseline_probe (id, probe_name, points) VALUES (2, 'dup', 1)");
        assertThatThrownBy(() -> exec(
                "INSERT INTO zhongshu_baseline_probe (id, probe_name, points) VALUES (3, 'dup', 1)"))
                .as("业务键 UNIQUE 必须拦截重复")
                .isInstanceOf(SQLException.class);
    }

    @Test
    void undoScriptRollbackRehearsal() throws Exception {
        flyway().migrate();
        execSqlFile("db/migration-undo/platform/V20260905.004__undo_zhongshu_admin_menus.sql");
        execSqlFile("db/migration-undo/platform/V20260905.002__undo_zhongshu_baseline_probe_index.sql");
        execSqlFile("db/migration-undo/platform/V20260905.001__undo_zhongshu_baseline.sql");
        execSqlFile("db/migration-undo/platform/V20260905.003__undo_p1c_platform_tables.sql");
        assertThat(tableExists("zhongshu_baseline_probe")).as("逆向脚本执行后表应消失").isFalse();
        assertThat(tableExists("outbox_event")).as("P1C 平台表应随 undo 消失").isFalse();

        // 清理迁移历史后可再次正向升级：证明“逆向脚本 + 重复迁移”链路可重复
        exec("DELETE FROM flyway_schema_history");
        flyway().migrate();
        assertThat(tableExists("zhongshu_baseline_probe")).isTrue();
        assertThat(tableExists("outbox_event")).isTrue();
        assertThat(historyCount()).isEqualTo(MIGRATION_COUNT);
    }

    @Test
    void adminMenuSeedAppliesWhenBaseSystemTablesPresent() throws Exception {
        // 模拟“底座 PG 基线先建 system/infra 表，再跑 Flyway”的正常引导顺序：
        // V004 菜单种子必须真实生效（列清单、行数、角色绑定全量校验）
        flyway().clean();
        exec("CREATE TABLE system_menu (\n" +
                "  id bigint NOT NULL PRIMARY KEY,\n" +
                "  name varchar(50) NOT NULL,\n" +
                "  permission varchar(100) NOT NULL DEFAULT '',\n" +
                "  type smallint NOT NULL,\n" +
                "  sort int NOT NULL DEFAULT 0,\n" +
                "  parent_id bigint NOT NULL DEFAULT 0,\n" +
                "  path varchar(200) DEFAULT '',\n" +
                "  icon varchar(100) DEFAULT '#',\n" +
                "  component varchar(255) DEFAULT NULL,\n" +
                "  component_name varchar(255) DEFAULT NULL,\n" +
                "  status smallint NOT NULL DEFAULT 0,\n" +
                "  visible boolean NOT NULL DEFAULT TRUE,\n" +
                "  keep_alive boolean NOT NULL DEFAULT TRUE,\n" +
                "  always_show boolean NOT NULL DEFAULT TRUE,\n" +
                "  creator varchar(64) DEFAULT '',\n" +
                "  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "  updater varchar(64) DEFAULT '',\n" +
                "  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "  deleted smallint NOT NULL DEFAULT 0\n" +
                ")");
        exec("CREATE TABLE system_role_menu (\n" +
                "  id bigint NOT NULL PRIMARY KEY,\n" +
                "  role_id bigint NOT NULL,\n" +
                "  menu_id bigint NOT NULL,\n" +
                "  creator varchar(64) DEFAULT '',\n" +
                "  create_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "  updater varchar(64) DEFAULT '',\n" +
                "  update_time timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,\n" +
                "  deleted smallint NOT NULL DEFAULT 0,\n" +
                "  tenant_id bigint NOT NULL DEFAULT 0\n" +
                ")");

        var result = flyway().migrate();
        assertThat(result.migrationsExecuted).isEqualTo(MIGRATION_COUNT);

        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM system_menu WHERE id BETWEEN 9500 AND 9519 AND deleted = 0")) {
            rs.next();
            assertThat(rs.getInt(1)).as("管理端业务菜单应完整种入").isEqualTo(11);
        }
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM system_role_menu WHERE role_id = 1 AND menu_id BETWEEN 9500 AND 9519 AND deleted = 0")) {
            rs.next();
            assertThat(rs.getInt(1)).as("超级管理员应绑定全部业务菜单").isEqualTo(11);
        }

        // 种子可重复执行（DELETE + INSERT 幂等）：重放迁移不会产生重复行
        // Later platform migrations now exist. Replay only the menu script to test its idempotency;
        // do not corrupt Flyway history or relax production out-of-order validation.
        execSqlFile("db/migration/platform/V20260905.004__zhongshu_admin_menus.sql");
        try (Connection c = newConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM system_menu WHERE id BETWEEN 9500 AND 9519")) {
            rs.next();
            assertThat(rs.getInt(1)).as("V004 重放后仅恢复自身种子（9512-9514 由 007/008 管理，Flyway 单次执行不受重放影响）").isEqualTo(9);
        }
    }

    @Test
    void concurrentFixtureDistinctAndConflictingInserts() throws Exception {
        flyway().migrate();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            // 并发插入互不相同：全部成功
            List<Future<Integer>> distinct = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                distinct.add(pool.submit(() -> {
                    start.await();
                    // 每线程独立 JDBC 连接：保证数据库层真实并发，而非单连接串行化
                    try (Connection taskConn = newConnection();
                         PreparedStatement ps = taskConn.prepareStatement(
                                 "INSERT INTO zhongshu_baseline_probe (id, probe_name, points) VALUES (?, ?, 0)")) {
                        ps.setLong(1, 1_000_000L + seq);
                        ps.setString(2, "distinct_" + seq);
                        ps.executeUpdate();
                        return 1;
                    } catch (SQLException e) {
                        return 0;
                    }
                }));
            }
            start.countDown();
            int okDistinct = 0;
            for (Future<Integer> f : distinct) {
                okDistinct += f.get();
            }
            assertThat(okDistinct).as("互不冲突的并发插入应全部成功").isEqualTo(threads);

            // 并发插入相同业务键：恰好一条成功
            CountDownLatch start2 = new CountDownLatch(1);
            List<Future<Integer>> conflict = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int seq = i;
                conflict.add(pool.submit(() -> {
                    start2.await();
                    try (Connection taskConn = newConnection();
                         PreparedStatement ps = taskConn.prepareStatement(
                                 "INSERT INTO zhongshu_baseline_probe (id, probe_name, points) VALUES (?, 'same_key', 0)")) {
                        ps.setLong(1, 2_000_000L + seq);
                        ps.executeUpdate();
                        return 1;
                    } catch (SQLException e) {
                        return 0;
                    }
                }));
            }
            start2.countDown();
            int okConflict = 0;
            for (Future<Integer> f : conflict) {
                okConflict += f.get();
            }
            assertThat(okConflict).as("相同业务键的并发插入只允许一条成功").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

}
