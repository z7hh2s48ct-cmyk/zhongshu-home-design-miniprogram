package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.catalog.CaseCatalogService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3B 合同测试（真实 PostgreSQL）：稳定游标分页（同面积不重页/漏页）、下架即时不可见、
 * 版本不可变与 STATE_VERSION_CONFLICT、收藏并发幂等、批量上下架逐项结果、首页只含已发布。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CaseP3BContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("zhongshu_design")
            .withUsername("zhongshu")
            .withPassword("zhongshu");

    private static final long ADMIN = 501L;

    private JdbcTemplate jdbc;
    private CaseCatalogService catalog;

    @BeforeAll
    void setUp() {
        SimpleDriverDataSource ds = new SimpleDriverDataSource();
        ds.setDriverClass(org.postgresql.Driver.class);
        ds.setUrl(PG.getJdbcUrl());
        ds.setUsername(PG.getUsername());
        ds.setPassword(PG.getPassword());
        DataSource dataSource = ds;
        jdbc = new JdbcTemplate(dataSource);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/platform", "classpath:db/migration/design")
                .load()
                .migrate();

        catalog = new CaseCatalogService(dataSource, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void cleanTables() {
        jdbc.execute("TRUNCATE design_case, design_case_version, design_case_asset, "
                + "case_publication, case_favorite, asset_rights_grant, asset");
    }

    private long createAndPublish(int buildingArea, String style) {
        long caseId = catalog.createCompanyCase(ADMIN, "案例 " + buildingArea + "㎡",
                null, style, 2, buildingArea, null, null, null, List.of(" tags-" + buildingArea));
        seedPublishableImages(caseId);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        return caseId;
    }

    // ========== 1. 稳定游标分页：同面积不重页/漏页 ==========

    private void seedPublishableImages(long caseId) {
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, caseId);
        for (String role : List.of("COVER", "FLOOR_PLAN")) {
            if (jdbc.queryForObject("SELECT count(*) FROM design_case_asset WHERE case_version_id=? AND asset_role=?", Integer.class, version, role) == 0)
                jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role) VALUES(?,?,?,?)",
                        com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), version, com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), role);
        }
        for (Long id : jdbc.queryForList("SELECT DISTINCT asset_id FROM design_case_asset WHERE case_version_id=?", Long.class, version)) {
            jdbc.update("INSERT INTO asset(id,object_key,owner_user_id,asset_type,source_type,sha256,declared_mime,size_bytes,upload_status,security_scan_status,moderation_status) "
                    + "VALUES(?,? ,501,'CASE_IMAGE','COMPANY','seed','image/png',10,'ACCEPTED','PASSED','PASSED') ON CONFLICT(id) DO NOTHING", id, "test/" + id);
            jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) VALUES(?,?,501,'test','PUBLIC_DISPLAY',now())",
                    com.baomidou.mybatisplus.core.toolkit.IdWorker.getId(), id);
        }
    }

    @Test
    void publicationRejectsMissingUnsafeAndUnlicensedImages() {
        long id = catalog.createCompanyCase(ADMIN, "缺图", null, "MODERN", 1, 100, null, null, null, null);
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        seedPublishableImages(id);
        jdbc.update("UPDATE asset SET security_scan_status='REJECTED'");
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE asset SET security_scan_status='PASSED'");
        jdbc.update("UPDATE asset_rights_grant SET status='WITHDRAWN'");
        assertThatThrownBy(() -> catalog.publish(id, "admin")).isInstanceOf(ServiceException.class);
        assertThat(catalog.getAdminCase(id).orElseThrow().publicationStatus()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_publication WHERE case_id=?", Integer.class, id)).isZero();
    }

    @Test
    void referenceActionRequiresEveryCurrentPlanGrantAndPreservesActualFloors() {
        long id = catalog.createCompanyCase(ADMIN, "参考权限", null, "MODERN", 3, 120, null, null, null, null);
        long version = jdbc.queryForObject("SELECT current_version_id FROM design_case WHERE id=?", Long.class, id);
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("INSERT INTO design_case_asset(id,case_version_id,asset_id,asset_role,floor_no) VALUES "
                + "(901,?,801,'FLOOR_PLAN',1),(902,?,802,'FLOOR_PLAN',3)", version, version);
        seedPublishableImages(id);
        catalog.publish(id, "admin");
        assertThat(catalog.getFloorPlans(id)).extracting(CaseCatalogService.FloorPlan::floorNo).containsExactly(1, 3);
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                + "VALUES (911,801,501,'test','GENERATION_REFERENCE',now()-interval '1 hour')");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("INSERT INTO asset_rights_grant(id,asset_id,grantor_user_id,rights_holder,scope,effective_at) "
                + "VALUES (912,802,501,'test','GENERATION_REFERENCE',now()-interval '1 hour')");
        assertThat(catalog.canUseForGeneration(id)).isTrue();
        jdbc.update("UPDATE asset_rights_grant SET expires_at=now()-interval '1 second' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("UPDATE asset_rights_grant SET expires_at=null, effective_at=now()+interval '1 hour' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        jdbc.update("UPDATE asset_rights_grant SET effective_at=now()-interval '1 hour', status='WITHDRAWN' WHERE id=912");
        assertThat(catalog.canUseForGeneration(id)).isFalse();
        catalog.offline(id, "test", "admin");
        assertThat(catalog.getPublishedCase(id)).isEmpty();
        assertThat(catalog.getAdminCase(id)).isPresent();
    }

    @Test
    void aiCaseParametersEditableForOperationsCorrection() {
        // 2026-10-02 运营决策：AI 案例开放参数编辑（图纸仍走投稿流程，requireImageEditable 拒绝 AI）
        long id = createAndPublish(120, "MODERN");
        jdbc.update("UPDATE design_case SET source_type='AI' WHERE id=?", id);
        catalog.updateCase(id, ADMIN, 1, "运营修正标题", null, "MODERN", 2, 120,
                null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
        assertThat(catalog.getAdminCase(id).orElseThrow().title()).isEqualTo("运营修正标题");
    }

    @Test
    @org.junit.jupiter.api.Disabled("Superseded: published company cases are editable with CAS")
    void publishedCompanyCaseCannotBeChangedWithoutWithdrawal() {
        long id = createAndPublish(120, "MODERN");
        assertThatThrownBy(() -> catalog.updateCase(id, ADMIN, 1, "直接改线上内容", null, "MODERN", 2, 120, null, null, null, null))
                .isInstanceOf(ServiceException.class);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(1);
        catalog.offline(id, "改图", "admin");
        catalog.updateCase(id, ADMIN, 1, "线下修改", null, "MODERN", 2, 120, null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
    }

    @Test
    void cursorPaginationCoversAllWithoutDuplication() {
        // 10 个案例，其中 4 个同面积 100㎡，顺序必须稳定
        List<Long> ids = IntStream.range(0, 10)
                .mapToObj(i -> createAndPublish(i < 4 ? 100 : 100 + i, "MODERN"))
                .collect(Collectors.toList());

        List<Long> seen = new java.util.ArrayList<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            var page = catalog.listPublishedCases(null, null, null, null, null, cursor, 3);
            page.list().forEach(c -> seen.add(c.caseId()));
            pages++;
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
            assertThat(pages).as("游标翻页防御性上限").isLessThan(20);
        }
        assertThat(seen).hasSize(10).doesNotHaveDuplicates();
        assertThat(seen).containsExactlyInAnyOrderElementsOf(ids);
    }

    // ========== 2. 下架即时不可见；可重新上架 ==========

    @Test
    void offlineHidesImmediatelyAndRepublishWorks() {
        long caseId = createAndPublish(120, "MODERN");
        assertThat(catalog.getPublishedCase(caseId)).isPresent();

        assertThat(catalog.offline(caseId, "户型调整", "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId)).as("下架即时不可见").isEmpty();
        var page = catalog.listPublishedCases(null, null, null, null, null, null, 20);
        assertThat(page.list()).isEmpty();

        // 公司案例可重新上架（新发布事实）
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_publication WHERE case_id = ?", Integer.class, caseId))
                .isEqualTo(3); // PUBLISH + OFFLINE + PUBLISH
    }

    // ========== 3. 版本不可变 + 过期版本冲突 ==========

    @Test
    void editingCreatesImmutableNewVersionAndConflictsOnStale() {
        long caseId = catalog.createCompanyCase(ADMIN, "v1 标题", null, "MODERN", 2, 100,
                null, null, null, null);
        createAndPublish(150, "MODERN"); // 干扰数据

        long v2 = catalog.updateCase(caseId, ADMIN, 1L, "v2 标题", "改描述", "MODERN", 3, 120,
                null, null, null, null);
        assertThat(v2).isNotEqualTo(1L);

        // 旧版本行不可变
        var v1 = jdbc.queryForMap(
                "SELECT title, floor_count, building_area FROM design_case_version "
                        + "WHERE case_id = ? AND version = 1", caseId);
        assertThat(v1.get("title")).isEqualTo("v1 标题");
        assertThat((int) v1.get("floor_count")).isEqualTo(2);

        // 详情读新版本
        var detail = catalog.getPublishedCase(caseId);
        // 未发布不可见 → 先发布
        seedPublishableImages(caseId);
        assertThat(catalog.publish(caseId, "admin")).isTrue();
        assertThat(catalog.getPublishedCase(caseId).orElseThrow().title()).isEqualTo("v2 标题");

        // 过期版本更新 → STATE_VERSION_CONFLICT
        assertThatThrownBy(() -> catalog.updateCase(caseId, ADMIN, 1L, "stale", null,
                "MODERN", 2, 100, null, null, null, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getCode()).isEqualTo(1_099_000_001));
    }

    // ========== 4. 收藏并发幂等 + 列表 ==========

    @Test
    void favoriteIsIdempotentUnderConcurrency() throws Exception {
        long caseId = createAndPublish(130, "MODERN");
        long userId = 701L;
        ExecutorServicePool pool = new ExecutorServicePool(5);
        List<java.util.concurrent.Callable<Boolean>> tasks = IntStream.range(0, 5)
                .<java.util.concurrent.Callable<Boolean>>mapToObj(i ->
                        () -> catalog.favorite(userId, caseId))
                .collect(Collectors.toList());
        int inserted = pool.run(tasks);
        pool.shutdown();
        assertThat(inserted).as("并发收藏只新增一次").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM case_favorite WHERE user_id = ? AND case_id = ?",
                Integer.class, userId, caseId)).isEqualTo(1);

        // 收藏列表可见；取消后消失
        var favPage = catalog.listFavorites(userId, null, 20);
        assertThat(favPage.list()).hasSize(1);
        assertThat(catalog.unfavorite(userId, caseId)).isTrue();
        assertThat(catalog.listFavorites(userId, null, 20).list()).isEmpty();
    }

    // ========== 5. 批量上下架逐项结果 ==========

    @Test
    void bulkActionReportsPerItemResult() {
        long published = createAndPublish(140, "MODERN");
        long draft = catalog.createCompanyCase(ADMIN, "草稿", null, "MODERN", 2, 141,
                null, null, null, null); // 未发布 → 下架失败

        var items = catalog.bulkPublicationAction(List.of(published, draft), false, "统一调整", "admin");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("success", true);
        assertThat(items.get(1)).containsEntry("success", false);
        assertThat(items.get(1)).containsEntry("errorCode", "PUBLICATION_VALIDATION_FAILED");
    }

    // ========== 6. 首页精选只含已发布 ==========

    @Test
    void homeFeaturedOnlyPublished() {
        createAndPublish(160, "MODERN");
        catalog.createCompanyCase(ADMIN, "草稿不精选", null, "MODERN", 2, 161,
                null, null, null, null);
        var featured = catalog.listPublishedCases(null, null, null, null, null, null, 6);
        assertThat(featured.list()).hasSize(1);
        assertThat(featured.list().get(0).publicationStatus()).isEqualTo("PUBLISHED");
    }

    // ========== 7. BOLA：未发布案例详情不可见 ==========

    @Test
    void unpublishedCaseDetailHidden() {
        long draft = catalog.createCompanyCase(ADMIN, "草稿", null, "MODERN", 2, 170,
                null, null, null, null);
        assertThat(catalog.getPublishedCase(draft)).isEmpty();
    }

    // ========== 8. 详情资产用版本行 id 关联（评审修复回归） ==========

    @Test
    void caseDetailReturnsAssetsViaVersionRowId() {
        long caseId = catalog.createCompanyCase(ADMIN, "带资产案例", "描述", "MODERN", 2, 180,
                null, null, null, null);
        long versionId = jdbc.queryForObject(
                "SELECT current_version_id FROM design_case WHERE id = ?", Long.class, caseId);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role) "
                        + "VALUES (?,?,?,'COVER')", 8001L, versionId, 9101L);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                        + "VALUES (?,?,?,'FLOOR_PLAN',1)", 8002L, versionId, 9102L);
        jdbc.update("INSERT INTO design_case_asset (id, case_version_id, asset_id, asset_role, floor_no) "
                        + "VALUES (?,?,?,'FLOOR_PLAN',2)", 8003L, versionId, 9103L);
        seedPublishableImages(caseId);
        catalog.publish(caseId, "admin");

        var detail = catalog.getPublishedCase(caseId).orElseThrow();
        assertThat(detail.coverAssetId()).isEqualTo("9101");
        assertThat(detail.floorPlanAssetIds()).containsExactly("9102", "9103");
    }

    // ========== 9. 管理端列表含草稿；批量对无效 id 逐项失败不整体 500 ==========

    @Test
    void adminPageShowsDraftsAndBulkIsolatesInvalidIds() {
        long draft = catalog.createCompanyCase(ADMIN, "草稿可见于管理端", null, "MODERN", 2, 190,
                null, null, null, null);
        assertThat(catalog.countCasesForAdmin(null, null)).isEqualTo(1);
        assertThat(catalog.pageCasesForAdmin(null, "DRAFT", 1, 20))
                .extracting(CaseCatalogService.CaseSummary::caseId).containsExactly(draft);
        assertThat(catalog.pageCasesForAdmin(null, "PUBLISHED", 1, 20)).isEmpty();

        long published = createAndPublish(191, "MODERN");
        var items = catalog.bulkPublicationAction(
                java.util.List.of(published, 999999999L, draft), false, "批量下架", "admin");
        assertThat(items).hasSize(3);
        assertThat(items.get(0)).as("已发布可下架").containsEntry("success", true);
        assertThat(items.get(1)).as("无效 id 逐项失败但不中断").containsEntry("success", false);
        assertThat(items.get(2)).as("草稿无可下架的发布态，逐项失败").containsEntry("success", false);
    }

    // ========== 10. 收藏游标翻页不漏页（评审修复回归） ==========

    @Test
    void favoritesPaginationUsesFavoriteRowIdCursor() {
        // 旧案例（先创建）后收藏 → 收藏行 id 大于案例 id，覆盖错位场景
        long oldCase = createAndPublish(200, "MODERN");
        long userId = 801L;
        List<Long> favorites = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long c = createAndPublish(201 + i, "MODERN");
            catalog.favorite(userId, c);
            favorites.add(c);
        }
        catalog.favorite(userId, oldCase);

        var page1 = catalog.listFavorites(userId, null, 2);
        assertThat(page1.list()).hasSize(2);
        assertThat(page1.nextCursor()).isNotNull();
        var page2 = catalog.listFavorites(userId, page1.nextCursor(), 2);
        assertThat(page2.list()).hasSize(2);
        var seen = new java.util.ArrayList<Long>();
        page1.list().forEach(c -> seen.add(c.caseId()));
        page2.list().forEach(c -> seen.add(c.caseId()));
        assertThat(seen).doesNotHaveDuplicates().hasSize(4);
    }

    @Test
    void publishedCompanyCaseEditingPreservesImmutableVersionAndCas() {
        long id = createAndPublish(120, "MODERN");
        assertThat(catalog.requireImageEditable(id, 1, "COVER", null).publicationStatus())
                .isEqualTo("PUBLISHED");
        catalog.updateCase(id, ADMIN, 1, "published edit", null, "MODERN", 2, 120,
                null, null, null, null);
        assertThat(catalog.getAdminCase(id).orElseThrow().version()).isEqualTo(2);
        assertThat(catalog.getAdminCase(id).orElseThrow().publicationStatus()).isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject("SELECT creator FROM design_case_version WHERE case_id=? AND version=2",
                String.class, id)).isEqualTo(String.valueOf(ADMIN));
        assertThatThrownBy(() -> catalog.updateCase(id, ADMIN, 1, "stale", null, "MODERN", 2,
                120, null, null, null, null)).isInstanceOf(ServiceException.class);
    }

    private static class ExecutorServicePool {
        private final java.util.concurrent.ExecutorService pool;

        ExecutorServicePool(int threads) {
            pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        }

        int run(List<java.util.concurrent.Callable<Boolean>> tasks) throws Exception {
            var latch = new java.util.concurrent.CountDownLatch(1);
            var futures = tasks.stream().map(t -> pool.submit(() -> {
                latch.await();
                try {
                    return t.call() ? 1 : 0;
                } catch (Exception e) {
                    return 0;
                }
            })).toList();
            latch.countDown();
            int ok = 0;
            for (var f : futures) {
                ok += f.get();
            }
            return ok;
        }

        void shutdown() {
            pool.shutdownNow();
        }

    }

}
