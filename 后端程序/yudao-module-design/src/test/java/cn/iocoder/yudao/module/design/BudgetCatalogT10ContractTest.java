package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.module.design.budget.BudgetAccountPriceService;
import cn.iocoder.yudao.module.design.budget.BudgetCatalogService;
import cn.iocoder.yudao.module.design.controller.admin.AdminBudgetCatalogController;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetCatalogVO.*;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetCatalogController;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/** Real PostgreSQL catalog/price transactions plus method-security and public projection contracts. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetCatalogT10ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private JdbcTemplate jdbc;
    private SimpleDriverDataSource ds;
    private BudgetCatalogService catalog;

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        jdbc = new JdbcTemplate(ds);
        catalog = new BudgetCatalogService(ds, new DataSourceTransactionManager(ds), new JdbcAuditPort(ds));
    }

    @BeforeEach
    void cleanOnlyThisContainer() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        jdbc.execute("TRUNCATE budget_catalog_command, budget_item_price, budget_line, budget_revision, budget_quote, budget_estimate, audit_event");
        jdbc.update("DELETE FROM budget_option WHERE id NOT BETWEEN 208001 AND 208035");
        jdbc.update("DELETE FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'");
        jdbc.update("DELETE FROM budget_region");
        jdbc.update("UPDATE budget_item SET enabled = FALSE, public_selectable = FALSE, version = 1");
        jdbc.update("UPDATE budget_option SET enabled = FALSE, version = 1");
    }

    @AfterEach
    void clearContexts() { TenantContextHolder.clear(); SecurityContextHolder.clearContext(); }

    @Test
    void workbookOptionDraftsHaveSourcesButNoRegionOrPublishedPrice() {
        assertThat(catalog.items(null, 1, 100).getTotal()).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_option", Integer.class)).isEqualTo(35);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_option WHERE enabled OR source_reference IS NULL", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item_price", Integer.class)).isZero();
        assertThat(catalog.publicRegions()).isEmpty();
        assertThat(catalog.options("207005", 1, 100).getList()).extracting(Option::selectionGroup).contains("DOOR", "WINDOW");
        assertThat(catalog.options("207008", 1, 100).getList()).extracting(Option::selectionGroup).containsExactly("WASHER", "STRIP", "WALL_LAMP");
    }

    @Test
    void regionsReplayOriginalResponseAndRejectStaleOrReusedRequests() {
        var body = Map.<String, Object>of("code", "HN_TEST", "name", "湖南测试");
        Region created = catalog.createRegion(9, "create", body);
        assertThat(created.enabled()).isFalse();
        Region updated = catalog.updateRegion(9, "update", created.regionId(), Map.of("name", "湖南", "enabled", true, "expectedVersion", 1));
        assertThat(updated.version()).isEqualTo(2);
        assertThat(catalog.createRegion(9, "create", body)).isEqualTo(created);
        assertThat(catalog.publicRegions()).extracting(value -> value.name()).containsExactly("湖南");
        assertCode(() -> catalog.updateRegion(9, "stale", created.regionId(), Map.of("name", "旧", "enabled", false, "expectedVersion", 1)), STATE_VERSION_CONFLICT.getCode());
        assertCode(() -> catalog.createRegion(9, "create", Map.of("code", "HN_TEST", "name", "异体")), IDEMPOTENCY_KEY_REUSED.getCode());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_catalog_command WHERE response IS NULL", Integer.class)).isZero();
    }

    @Test
    void strictFieldsIdsMoneyAndQuantityRulesFailBeforeAnyWrite() {
        for (var body : List.of(Map.<String, Object>of("code", " BAD", "name", "x"), Map.<String, Object>of("code", "GOOD", "name", "x", "actorId", "1"))) {
            assertCode(() -> catalog.createRegion(9, "bad", body), BUDGET_INPUT_INVALID.getCode());
        }
        assertCode(() -> catalog.options("9007199254740993e0", 1, 20), BUDGET_INPUT_INVALID.getCode());
        assertCode(() -> catalog.options("9223372036854775808", 1, 20), BUDGET_INPUT_INVALID.getCode());
        assertCode(() -> catalog.items(null, 0, 20), BUDGET_INPUT_INVALID.getCode());
        for (Object money : List.of(-1, 100000001L, 1.0d, "100", new java.math.BigDecimal("1.1"))) {
            assertCode(() -> catalog.createPrice(9, "bad", Map.of("regionCode", "HN", "optionId", "208001", "unitPriceCents", money)), BUDGET_INPUT_INVALID.getCode());
        }
        var badOption = new LinkedHashMap<String, Object>(Map.of("itemId", "207001", "code", "BAD", "label", "x", "selectionGroup", "F", "unit", "METER", "quantitySource", "PROJECT_QUANTITY", "quantityKey", "UNKNOWN"));
        assertCode(() -> catalog.createOption(9, "bad", badOption), BUDGET_INPUT_INVALID.getCode());
        badOption.remove("quantityKey");
        assertCode(() -> catalog.createOption(9, "bad-missing-key", badOption), BUDGET_INPUT_INVALID.getCode());
        badOption.put("quantitySource", "FIXED_ONE"); badOption.remove("quantityKey");
        assertCode(() -> catalog.createOption(9, "bad", badOption), BUDGET_INPUT_INVALID.getCode());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_catalog_command", Integer.class)).isZero();
    }

    @Test
    void standardIdentitiesAndStandardQuantityRulesCannotBeRewritten() {
        assertCode(() -> catalog.updateItem(9, "bad", "207001", Map.of("code", "RAILING", "name", "x", "enabled", true, "publicSelectable", true, "expectedVersion", 1)), BUDGET_INPUT_INVALID.getCode());
        assertCode(() -> catalog.createItem(9, "bad", Map.of("code", "NEW", "name", "x", "category", "BODY", "source", "STANDARD")), BUDGET_INPUT_INVALID.getCode());
        var request = new LinkedHashMap<String, Object>(optionUpdate(catalog.options("207001", 1, 100).getList().get(0)));
        request.put("quantitySource", "BUILDING_AREA");
        assertCode(() -> catalog.updateOption(9, "bad-rule", "208001", request), BUDGET_INPUT_INVALID.getCode());
        request.put("quantitySource", "FOOTPRINT_AREA"); request.put("selectionGroup", "STRUCTURE");
        assertCode(() -> catalog.updateOption(9, "bad-group", "208001", request), BUDGET_INPUT_INVALID.getCode());
        assertThat(catalog.items(null, 1, 100).getList()).filteredOn(value -> "STANDARD".equals(value.source())).hasSize(10);
    }

    @Test
    void customTemplatesArePrivateUntilExplicitCompleteRegionalPublication() {
        region("HN"); region("OTHER");
        Item custom = custom();
        assertThat(custom.publicSelectable()).isFalse();
        assertCode(() -> catalog.updateItem(9, "too-soon", custom.itemId(), itemUpdate(custom, true)), BUDGET_INPUT_INVALID.getCode());
        Option option = customOption(custom);
        assertThat(catalog.publicOptions("HN").items()).isEmpty();
        Price draft = price("HN", option.optionId(), 18000L, "2026-01-01T00:00:00Z", null);
        catalog.publishPrice(9, "publish", draft.priceId(), state(1));
        Item visible = catalog.updateItem(9, "open", custom.itemId(), itemUpdate(custom, true));
        assertThat(catalog.publicOptions("HN").items()).extracting(value -> value.itemId()).containsExactly(custom.itemId());
        assertThat(catalog.publicOptions("OTHER").items()).isEmpty();
        catalog.updateItem(9, "disable", visible.itemId(), Map.of("name", visible.name(), "enabled", false, "publicSelectable", false, "expectedVersion", 2));
        assertThat(catalog.publicOptions("HN").items()).isEmpty();
    }

    @Test
    void standardMissingPricesNeverFallBackAcrossRegionsAndResponsesAreExplicit() {
        region("HN"); region("OTHER");
        enableFoundation();
        Price draft = price("HN", "208001", 52000L, "2026-01-01T00:00:00Z", null);
        assertThat(catalog.publicOptions("HN").items().get(0).options().get(0).availability()).isEqualTo("MISSING_PRICE");
        catalog.publishPrice(9, "publish", draft.priceId(), state(1));
        assertThat(catalog.publicOptions("HN").items().get(0).options().get(0).availability()).isEqualTo("AVAILABLE");
        assertThat(catalog.publicOptions("OTHER").items().get(0).options().get(0).availability()).isEqualTo("MISSING_PRICE");
        String json = JsonUtils.toJsonString(catalog.publicOptions("HN"));
        assertThat(json).doesNotContain("unitPrice", "quantity", "sourceReference", "freeReason", "published", "52000", "effectiveAt");
        assertThat(JsonUtils.parseObject(json, Map.class)).containsOnlyKeys("items");
        assertCode(() -> catalog.publicOptions("UNKNOWN"), BUDGET_INPUT_INVALID.getCode());
    }

    @Test
    void draftMissingPriceAndExplicitFreeRemainDifferentAndPublicationIsImmutable() {
        region("HN"); enableFoundation();
        Price missing = price("HN", "208001", null, null, null);
        assertThat(missing.unitPriceCents()).isNull();
        assertCode(() -> catalog.publishPrice(9, "missing", missing.priceId(), state(1)), BUDGET_INPUT_INVALID.getCode());
        assertCode(() -> price("HN", "208001", 0L, "2026-01-01T00:00:00Z", null), BUDGET_INPUT_INVALID.getCode());
        Price free = catalog.updatePrice(9, "free", missing.priceId(), Map.of("unitPriceCents", 0, "freeReason", "明确免费", "effectiveAt", "2026-01-01T00:00:00Z", "expectedVersion", 1));
        assertThat(free.unitPriceCents()).isZero();
        Price published = catalog.publishPrice(9, "publish", free.priceId(), state(2));
        assertThat(published.publishedBy()).isEqualTo("9");
        assertThat(published.effectiveAt()).isEqualTo("2026-01-01T00:00:00Z");
        assertCode(() -> catalog.updatePrice(9, "mutate", published.priceId(), Map.of("unitPriceCents", 100, "expectedVersion", 3)), STATE_VERSION_CONFLICT.getCode());
        catalog.disablePrice(9, "disable", published.priceId(), state(3));
        assertThat(catalog.publishPrice(9, "publish", free.priceId(), state(2))).isEqualTo(published);
        assertThat(catalog.prices("HN", "208001", null, 1, 20).getList().get(0).status()).isEqualTo("DISABLED");
    }

    @Test
    void adjacentHalfOpenIntervalsAreAllowedAndOverlapsRejected() {
        region("HN"); enableFoundation();
        Price first = price("HN", "208001", 100L, "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z");
        Price next = price("HN", "208001", 200L, "2026-02-01T00:00:00Z", null);
        catalog.publishPrice(9, "publish-first", first.priceId(), state(1));
        catalog.publishPrice(9, "publish-next", next.priceId(), state(1));
        Price overlap = price("HN", "208001", 300L, "2026-01-31T23:59:59Z", "2026-02-01T00:00:01Z");
        assertCode(() -> catalog.publishPrice(9, "overlap", overlap.priceId(), state(1)), BUDGET_PRICE_CONFLICT.getCode());
        assertThat(catalog.prices("HN", null, "PUBLISHED", 1, 100).getTotal()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_item_price WHERE option_id = 208001 AND status = 'PUBLISHED' "
                + "AND effective_at <= '2026-02-01T00:00:00Z' AND (expires_at IS NULL OR expires_at > '2026-02-01T00:00:00Z')", Long.class)).isEqualTo(200L);
    }

    @Test
    void competingPublishersSerializeAndExactlyOneConflictingPriceCommits() throws Exception {
        region("HN"); enableFoundation();
        Price a = price("HN", "208001", 100L, "2026-01-01T00:00:00Z", null);
        Price b = price("HN", "208001", 200L, "2026-01-01T00:00:00Z", null);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> one = pool.submit(() -> publishAfter(start, a, 9));
            Future<Integer> two = pool.submit(() -> publishAfter(start, b, 10));
            start.countDown();
            assertThat(List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS))).containsExactlyInAnyOrder(0, BUDGET_PRICE_CONFLICT.getCode());
        } finally { pool.shutdownNow(); }
        assertThat(catalog.prices("HN", null, "PUBLISHED", 1, 100).getTotal()).isEqualTo(1L);
    }

    @Test
    void auditFailureRollsBackPriceMutationAndReplayReceipt() {
        region("HN"); enableFoundation();
        Price draft = price("HN", "208001", 100L, "2026-01-01T00:00:00Z", null);
        var failing = new BudgetCatalogService(ds, new DataSourceTransactionManager(ds), event -> { throw new IllegalStateException("audit unavailable"); });
        assertThatThrownBy(() -> failing.publishPrice(9, "rollback", draft.priceId(), state(1))).isInstanceOf(IllegalStateException.class);
        assertThat(catalog.prices("HN", null, null, 1, 20).getList().get(0).status()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_catalog_command WHERE idempotency_key = 'rollback'", Integer.class)).isZero();
        assertThat(catalog.publishPrice(9, "rollback", draft.priceId(), state(1)).status()).isEqualTo("PUBLISHED");
    }

    @Test
    void platformRequestTenantCannotSelectAnotherBusinessCatalogAndIdsRemainStrings() {
        Region zero = region("HN");
        TenantContextHolder.setTenantId(2L);
        assertThat(catalog.items(null, 1, 100).getTotal()).isEqualTo(10L);
        assertThat(catalog.publicRegions()).hasSize(1);
        assertThat(region("HN")).isEqualTo(zero);
        assertThat(Long.parseLong(zero.regionId())).isGreaterThan(9007199254740991L);
        assertThat(JsonUtils.toJsonString(zero)).contains("\"regionId\":\"" + zero.regionId() + "\"");
        assertThat(catalog.regions(1, 100).getTotal()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM budget_region", Long.class)).isZero();
    }

    @Test
    void optionPriceUnitCannotBeReinterpretedAfterAnyPriceDraftExists() {
        region("HN"); Item item = custom(); Option option = customOption(item);
        price("HN", option.optionId(), 100L, null, null);
        var body = new LinkedHashMap<>(optionUpdate(option));
        body.put("unit", "SQM"); body.put("quantitySource", "BUILDING_AREA"); body.put("quantityKey", null);
        assertCode(() -> catalog.updateOption(9, "change-unit", option.optionId(), body), STATE_VERSION_CONFLICT.getCode());
        var renameBody = new LinkedHashMap<>(optionUpdate(option)); renameBody.put("label", "新名称");
        assertThat(catalog.updateOption(9, "rename", option.optionId(), renameBody).label()).isEqualTo("新名称");
    }

    @Test
    void appRequiresUnrestrictedIdentityBeforeCatalogAccess() {
        var app = new AppBudgetCatalogController();
        ReflectionTestUtils.setField(app, "budgetCatalogService", catalog);
        IdentitySessionPort identities = token -> "owner".equals(token)
                ? Optional.of(new IdentitySessionPort.SessionContext(1, "app", "id", false))
                : "restricted".equals(token) ? Optional.of(new IdentitySessionPort.SessionContext(1, "app", "id", true)) : Optional.empty();
        ReflectionTestUtils.setField(app, "identitySessionPort", identities);
        assertThatThrownBy(() -> app.regions("Bearer restricted")).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(IdentitySessionPort.ACCESS_GRANT_REQUIRED));
        assertThatThrownBy(() -> app.options("HN", null)).isInstanceOfSatisfying(ServiceException.class,
                e -> assertThat(e.getCode()).isEqualTo(401));
        assertThat(app.regions("Bearer owner").getData()).isEmpty();
    }

    @Test
    void methodSecuritySeparatesQueryConfigureAndPublicationAndServerSuppliesActor() {
        PermissionProbe permissions = new PermissionProbe();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(SecurityConfig.class);
            context.registerBean("ss", PermissionProbe.class, () -> permissions);
            context.registerBean(BudgetCatalogService.class, () -> catalog);
            // T14：管理控制器新增的覆盖价只读依赖，沿用同一真库夹具
            context.registerBean(BudgetAccountPriceService.class,
                    () -> new BudgetAccountPriceService(ds, new DataSourceTransactionManager(ds), new JdbcAuditPort(ds)));
            context.registerBean(AdminBudgetCatalogController.class);
            context.refresh();
            var admin = context.getBean(AdminBudgetCatalogController.class);
            SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setUserType(2).setTenantId(1L), new MockHttpServletRequest());
            assertThatThrownBy(() -> admin.regions(1, 20)).isInstanceOf(AccessDeniedException.class);
            permissions.allowed = Set.of("design:budget:query");
            assertThat(admin.regions(1, 20).getData().getTotal()).isZero();
            assertThatThrownBy(() -> admin.createRegion("create", Map.of("code", "HN", "name", "test"))).isInstanceOf(AccessDeniedException.class);
            permissions.allowed = Set.of("design:budget:configure");
            admin.createRegion("create", Map.of("code", "HN", "name", "test"));
            assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_event", String.class)).isEqualTo("99");
            assertThatThrownBy(() -> admin.publishPrice("1", "publish", state(1))).isInstanceOf(AccessDeniedException.class);
            permissions.allowed = Set.of("design:budget:price-publish");
            assertThatThrownBy(() -> admin.publishPrice("1", "publish", state(1))).isInstanceOf(ServiceException.class);
            assertThatThrownBy(() -> admin.createRegion("create2", Map.of("code", "OTHER", "name", "test"))).isInstanceOf(AccessDeniedException.class);
            permissions.allowed = Set.of("design:budget:query", "design:budget:configure", "design:budget:price-publish");
            SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setUserType(2).setTenantId(2L), new MockHttpServletRequest());
            assertThatThrownBy(() -> admin.regions(1, 20)).isInstanceOf(AccessDeniedException.class);
            assertThatThrownBy(() -> admin.createItem("foreign", Map.of("code", "FOREIGN", "name", "foreign", "category", "BODY"))).isInstanceOf(AccessDeniedException.class);
            SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(99L).setUserType(2).setTenantId(1L).setVisitTenantId(2L), new MockHttpServletRequest());
            assertThatThrownBy(() -> admin.options(null, 1, 100)).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfig {}
    public static class PermissionProbe {
        Set<String> allowed = Set.of();
        public boolean hasPermission(String permission) { return allowed.contains(permission); }
    }

    private Region region(String code) { return catalog.createRegion(9, "region-" + code, Map.of("code", code, "name", code, "enabled", true)); }
    private Item custom() { return catalog.createItem(9, "custom", Map.of("code", "DRAIN", "name", "排水沟施工", "category", "EXTERIOR", "enabled", true)); }
    private Option customOption(Item item) { return catalog.createOption(9, "custom-option", Map.of("itemId", item.itemId(), "code", "DEFAULT", "label", "施工", "selectionGroup", "DEFAULT", "unit", "METER", "quantitySource", "PROJECT_QUANTITY", "quantityKey", "CULTURE_STONE_LENGTH", "enabled", true)); }
    private Price price(String region, String optionId, Long cents, String effective, String expires) {
        var body = new LinkedHashMap<String, Object>(); body.put("regionCode", region); body.put("optionId", optionId); body.put("unitPriceCents", cents);
        body.put("effectiveAt", effective); body.put("expiresAt", expires);
        return catalog.createPrice(9, UUID.randomUUID().toString(), body);
    }
    private void enableFoundation() {
        catalog.updateItem(9, "foundation", "207001", Map.of("name", "地基基础", "enabled", true, "publicSelectable", true, "expectedVersion", 1));
        Option option = catalog.options("207001", 1, 100).getList().get(0);
        catalog.updateOption(9, "foundation-option", option.optionId(), optionUpdate(option));
    }
    private Map<String, Object> itemUpdate(Item item, boolean publicSelectable) { return Map.of("name", item.name(), "enabled", item.enabled(), "publicSelectable", publicSelectable, "expectedVersion", item.version()); }
    private Map<String, Object> optionUpdate(Option option) {
        var body = new LinkedHashMap<String, Object>(); body.put("label", option.label()); body.put("selectionGroup", option.selectionGroup()); body.put("unit", option.unit());
        body.put("quantitySource", option.quantitySource()); body.put("quantityKey", option.quantityKey()); body.put("sourceReference", option.sourceReference()); body.put("enabled", true); body.put("expectedVersion", option.version());
        return body;
    }
    private static Map<String, Object> state(int version) { return Map.of("expectedVersion", version, "reason", "测试明确操作"); }
    private int publishAfter(CountDownLatch start, Price price, long actor) throws InterruptedException {
        start.await();
        try { catalog.publishPrice(actor, "compete", price.priceId(), state(1)); return 0; }
        catch (ServiceException expected) { return expected.getCode(); }
    }
    private static void assertCode(Runnable action, int code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(code));
    }
}
