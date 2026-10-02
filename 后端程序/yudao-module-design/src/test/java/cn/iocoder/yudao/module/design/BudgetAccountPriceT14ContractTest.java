package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.design.budget.BudgetAccountPriceService;
import cn.iocoder.yudao.module.design.budget.ItemizedBudgetService;
import cn.iocoder.yudao.module.design.controller.app.AppAccountPriceController;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetCatalogController;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_ACCOUNT_PRICE_INVALID;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T14: 账号覆盖价合同——真库迁移、覆盖/恢复、两层取价与快照来源、目录可用性联动、门禁与冻结不变量。
 * baseline golden fixture 与 T10 一致：13 行、总额 48,202,000 分；208001=地基条基 52,000 分/㎡ × 120㎡。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetAccountPriceT14ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final long PROJECT = 9007199254741001L;
    private static final long VERSION = 9007199254742001L;
    private static final long REQUIREMENT = 9007199254743001L;
    private static final long REGION = 810001L;
    private static final long TOTAL = 48_202_000L;
    private static final String BASE = "/design/v1/budget";
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final List<String> OPTIONS = List.of("208001", "208006", "208009", "208012", "208015",
            "208020", "208023", "208026", "208029", "208030", "208032", "208034", "208035");
    private static final long[] PRICES = {52_000, 98_000, 52_000, 12_000, 1_800_000, 49_000,
            12_000, 12_000, 12_000, 6_000, 8_500, 500_000, 500_000};
    private SimpleDriverDataSource ds;
    private DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private DesignProjectService projects;
    private ItemizedBudgetService budgets;
    private BudgetAccountPriceService accounts;
    private MockMvc mvc;
    private final IdentitySessionPort identities = token -> switch (token == null ? "" : token) {
        case "owner" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "owner", false));
        case "other" -> Optional.of(new IdentitySessionPort.SessionContext(2, "test", "other", false));
        case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "owner", true));
        default -> Optional.empty();
    };

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        transactions = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        projects = new DesignProjectService(ds, transactions, mock(cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort.class), null, null, null);
    }

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE budget_quote, budget_line, budget_revision, budget_estimate, budget_item_price, budget_account_price, "
                + "budget_region, budget_app_command, budget_rule_version, design_project, design_requirement_snapshot, "
                + "design_result_version, audit_event");
        jdbc.update("DELETE FROM budget_option WHERE item_id IN (SELECT id FROM budget_item WHERE source = 'CUSTOM_TEMPLATE')");
        jdbc.update("DELETE FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'");
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE, deleted = FALSE");
        jdbc.update("UPDATE budget_option SET enabled = TRUE, deleted = FALSE");
        jdbc.update("INSERT INTO budget_region (id, code, name, enabled) VALUES (?, 'TEST_A', '金样测试地区', TRUE)", REGION);
        for (int i = 0; i < OPTIONS.size(); i++) price(820001L + i, REGION, Long.parseLong(OPTIONS.get(i)), PRICES[i]);
        project(PROJECT, 1, REQUIREMENT, inputs());
        version(VERSION, PROJECT);
        accounts = new BudgetAccountPriceService(ds, transactions, new JdbcAuditPort(ds));
        budgets = new ItemizedBudgetService(ds, transactions, projects, new JdbcAuditPort(ds));
        var budgetController = new AppBudgetController();
        ReflectionTestUtils.setField(budgetController, "budgetService", new cn.iocoder.yudao.module.design.budget.BudgetService(ds, projects));
        ReflectionTestUtils.setField(budgetController, "itemizedBudgetService", budgets);
        ReflectionTestUtils.setField(budgetController, "designProjectService", projects);
        ReflectionTestUtils.setField(budgetController, "identitySessionPort", identities);
        var priceController = new AppAccountPriceController();
        ReflectionTestUtils.setField(priceController, "accountPriceService", accounts);
        ReflectionTestUtils.setField(priceController, "identitySessionPort", identities);
        ReflectionTestUtils.setField(priceController, "verifiedAccountRateLimiter",
                mock(cn.iocoder.yudao.framework.ratelimiter.core.VerifiedAccountRateLimiter.class));
        var catalogController = new AppBudgetCatalogController();
        ReflectionTestUtils.setField(catalogController, "budgetCatalogService",
                new cn.iocoder.yudao.module.design.budget.BudgetCatalogService(ds, transactions, new JdbcAuditPort(ds)));
        ReflectionTestUtils.setField(catalogController, "identitySessionPort", identities);
        mvc = MockMvcBuilders.standaloneSetup(budgetController, priceController, catalogController)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .setControllerAdvice(new GlobalExceptionHandler("t14-contract", mock(ApiErrorLogCommonApi.class))).build();
    }

    @Test
    void uniqueIndexKeepsOneActiveOverridePerOptionAndResetHistoryRows() {
        jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, status) VALUES (1, 1, 208001, 60_000, 'ACTIVE')");
        // 同账号同选项第二条 ACTIVE 被部分唯一索引拒绝
        assertThatThrownBy(() -> jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, status) VALUES (2, 1, 208001, 61_000, 'ACTIVE')"))
                .isInstanceOf(DuplicateKeyException.class);
        // 恢复（REMOVED）后可再次覆盖；他人同选项不受影响
        jdbc.update("UPDATE budget_account_price SET status = 'REMOVED' WHERE id = 1");
        jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, status) VALUES (3, 1, 208001, 62_000, 'ACTIVE')");
        jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, status) VALUES (4, 2, 208001, 70_000, 'ACTIVE')");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_account_price WHERE status = 'ACTIVE'", Integer.class)).isEqualTo(2);
    }

    @Test
    void myPricesListBaselineOverrideAndMissingStatesWithoutCustomTemplates() throws Exception {
        custom(830001, 830002);
        price(830003, REGION, 830002, 360_000);
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE WHERE id = 830001");
        jdbc.update("UPDATE budget_item_price SET status = 'DRAFT' WHERE option_id = 208009");
        setOverride("208001", 60_000, "当地人工更贵");
        JsonNode data = ok(get(BASE + "/my-prices?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        assertThat(data.path("regionCode").asText()).isEqualTo("TEST_A");
        assertThat(data.path("regionName").asText()).isEqualTo("金样测试地区");
        JsonNode foundation = group(data, "FOUNDATION");
        assertThat(foundation.path("options").get(0).path("optionId").asText()).isEqualTo("208001");
        assertThat(foundation.path("options").get(0).path("baselinePriceCents").asLong()).isEqualTo(52_000L);
        assertThat(foundation.path("options").get(0).path("myPriceCents").asLong()).isEqualTo(60_000L);
        assertThat(foundation.path("options").get(0).path("priceSource").asText()).isEqualTo("ACCOUNT_OVERRIDE");
        assertThat(foundation.path("options").get(0).path("reason").asText()).isEqualTo("当地人工更贵");
        assertThat(foundation.path("options").get(0).path("updatedAt").isTextual()).isTrue();
        assertThat(option(data, "208006").path("priceSource").asText()).isEqualTo("DEFAULT");
        assertThat(option(data, "208006").path("myPriceCents").isNull()).isTrue();
        assertThat(option(data, "208009").path("priceSource").asText()).isEqualTo("MISSING");
        assertThat(option(data, "208009").path("baselinePriceCents").isNull()).isTrue();
        // 自定义模板项不进入用户覆盖清单
        assertThat(data.toString()).doesNotContain("830002", "CUSTOM_830001");
    }

    @Test
    void setUpsertRejectsInvalidOptionsAmountsAndFieldsWithoutWrites() throws Exception {
        custom(830001, 830002);
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE WHERE id = 830001");
        for (String badBody : List.of(
                "{\"unitPriceCents\":0}", "{\"unitPriceCents\":-5}", "{\"unitPriceCents\":100000001}",
                "{\"unitPriceCents\":1.5}", "{\"unitPriceCents\":\"60000\"}", "{\"reason\":\"缺少金额\"}",
                "{\"unitPriceCents\":60000,\"reason\":\" \"}", "{\"unitPriceCents\":60000,\"extra\":1}")) {
            var response = response(put(BASE + "/my-prices/208001?regionCode=TEST_A")
                    .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON).content(badBody));
            assertThat(response.path("code").asInt()).as(badBody).isEqualTo(BUDGET_ACCOUNT_PRICE_INVALID.getCode());
        }
        // 自定义模板选项、未知/非标准选项、未知地区均拒绝
        for (String path : List.of("830002", "999999")) {
            assertThat(response(put(BASE + "/my-prices/" + path + "?regionCode=TEST_A")
                    .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"unitPriceCents\":60000}")).path("code").asInt()).isEqualTo(BUDGET_ACCOUNT_PRICE_INVALID.getCode());
        }
        assertThat(response(put(BASE + "/my-prices/208001?regionCode=NOWHERE")
                .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceCents\":60000}")).path("code").asInt()).isEqualTo(BUDGET_ACCOUNT_PRICE_INVALID.getCode());
        assertThat(response(put(BASE + "/my-prices/0?regionCode=TEST_A")
                .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON)
                .content("{\"unitPriceCents\":60000}")).path("code").asInt()).isEqualTo(BUDGET_INPUT_INVALID.getCode());
        assertThat(count("budget_account_price")).isZero();
        assertThat(count("audit_event")).isZero();
    }

    @Test
    void overrideAppliesOnlyToOwnerNewEstimatesAndFreezesAccountPriceIdentity() throws Exception {
        setOverride("208001", 60_000, null);
        setOverride("208001", 61_000, "再调一次");
        // 同选项仅一条 ACTIVE，版本随更新递增
        assertThat(count("budget_account_price")).isEqualTo(1);
        long overrideId = jdbc.queryForObject("SELECT id FROM budget_account_price", Long.class);
        assertThat(jdbc.queryForObject("SELECT version FROM budget_account_price", Integer.class)).isEqualTo(2);
        JsonNode result = create("owner-key-1");
        assertThat(result.path("totalCents").asLong()).isEqualTo(TOTAL - 52_000L * 120 + 61_000L * 120);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_line WHERE option_id = 208001", Long.class)).isEqualTo(61_000L);
        assertThat(jdbc.queryForObject("SELECT price_version_id FROM budget_line WHERE option_id = 208001", Long.class)).isEqualTo(820001L);
        JsonNode overrideSnapshot = JSON.readTree(jdbc.queryForObject("SELECT pricing_snapshot::text FROM budget_line WHERE option_id = 208001", String.class));
        assertThat(overrideSnapshot.path("priceSource").asText()).isEqualTo("ACCOUNT_OVERRIDE");
        assertThat(overrideSnapshot.path("accountPriceId").asLong()).isEqualTo(overrideId);
        assertThat(overrideSnapshot.path("accountPriceVersion").asInt()).isEqualTo(2);
        assertThat(overrideSnapshot.path("priceVersion").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_line WHERE option_id = 208006", Long.class)).isEqualTo(98_000L);
        JsonNode defaultSnapshot = JSON.readTree(jdbc.queryForObject("SELECT pricing_snapshot::text FROM budget_line WHERE option_id = 208006", String.class));
        assertThat(defaultSnapshot.path("priceSource").asText()).isEqualTo("DEFAULT");
        assertThat(defaultSnapshot.has("accountPriceId")).isFalse();
        // 他账号新测算仍用基准价
        project(PROJECT + 1, 2, REQUIREMENT + 1, inputs());
        version(VERSION + 1, PROJECT + 1);
        assertThat(createForOther().path("totalCents").asLong()).isEqualTo(TOTAL);
    }

    @Test
    void resetRestoresBaselineOnNextEstimateAndKeepsRemovedHistoryRow() throws Exception {
        setOverride("208001", 60_000, null);
        assertThat(create("reset-before").path("totalCents").asLong()).isEqualTo(TOTAL - 52_000L * 120 + 60_000L * 120);
        JsonNode after = ok(delete(BASE + "/my-prices/208001?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        assertThat(after.path("myPriceCents").isNull()).isTrue();
        assertThat(after.path("priceSource").asText()).isEqualTo("DEFAULT");
        assertThat(after.path("baselinePriceCents").asLong()).isEqualTo(52_000L);
        assertThat(create("reset-after").path("totalCents").asLong()).isEqualTo(TOTAL);
        // 幂等重复恢复 + 历史行保留
        ok(delete(BASE + "/my-prices/208001?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        assertThat(count("budget_account_price")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_account_price", String.class)).isEqualTo("REMOVED");
    }

    @Test
    void overrideFillsMissingBaselinePriceAndCatalogMarksAvailableOnlyForOwner() throws Exception {
        jdbc.update("UPDATE budget_item_price SET status = 'DRAFT' WHERE option_id = 208009");
        // 无覆盖：目录 MISSING_PRICE，测算缺价
        JsonNode before = ok(get(BASE + "/options?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        assertThat(option(before, "208009").path("availability").asText()).isEqualTo("MISSING_PRICE");
        assertThat(create("missing-roof").path("completeness").asText()).isEqualTo("INCOMPLETE");
        // 有覆盖：本人目录 AVAILABLE，测算用覆盖价
        setOverride("208009", 77_000, null);
        JsonNode after = ok(get(BASE + "/options?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        assertThat(option(after, "208009").path("availability").asText()).isEqualTo("AVAILABLE");
        JsonNode filled = create("filled-roof");
        assertThat(filled.path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(filled.path("totalCents").asLong()).isEqualTo(TOTAL - 52_000L * 112 + 77_000L * 112);
        // 他人视角仍 MISSING_PRICE
        project(PROJECT + 1, 2, REQUIREMENT + 1, inputs());
        version(VERSION + 1, PROJECT + 1);
        createForOther();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_line l "
                + "JOIN budget_revision r ON r.id = l.revision_id JOIN budget_estimate e ON e.id = r.estimate_id "
                + "WHERE e.user_id = 2 AND l.status = 'MISSING_PRICE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void savedBudgetStaysFrozenWhenOverridesChangeAfterwards() throws Exception {
        JsonNode created = create("freeze-me");
        ok(post("/design/v1/budget-estimates/{id}/save", id(created)).header("Authorization", "Bearer owner").header("Idempotency-Key", "freeze-save"));
        String linesBefore = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(l) ORDER BY id)::text FROM budget_line l", String.class);
        setOverride("208001", 99_999, null);
        setOverride("208006", 1, null);
        JsonNode detail = ok(get("/design/v1/budget-estimates/{id}", id(created)).header("Authorization", "Bearer owner"));
        // 已保存标记随保存动作变化；金额与公开快照必须逐字段冻结
        ((com.fasterxml.jackson.databind.node.ObjectNode) detail).put("saved", false);
        assertThat(detail).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(l) ORDER BY id)::text FROM budget_line l", String.class)).isEqualTo(linesBefore);
        // 覆盖只影响新测算：208001→99,999×120㎡、208006→1×240㎡（基准建筑面积=120×2层）
        assertThat(create("freeze-new").path("totalCents").asLong())
                .isEqualTo(TOTAL + (99_999L - 52_000L) * 120 + (1L - 98_000L) * 240);
    }

    @Test
    void myPriceEndpointsRequireUnrestrictedSession() throws Exception {
        for (String token : List.of("restricted", "invalid")) {
            int expected = "restricted".equals(token) ? IdentitySessionPort.ACCESS_GRANT_REQUIRED : 401;
            assertThat(response(get(BASE + "/my-prices?regionCode=TEST_A").header("Authorization", "Bearer " + token)).path("code").asInt()).isEqualTo(expected);
            assertThat(response(put(BASE + "/my-prices/208001?regionCode=TEST_A").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content("{\"unitPriceCents\":60000}")).path("code").asInt()).isEqualTo(expected);
            assertThat(response(delete(BASE + "/my-prices/208001?regionCode=TEST_A").header("Authorization", "Bearer " + token)).path("code").asInt()).isEqualTo(expected);
        }
        assertThat(count("budget_account_price")).isZero();
    }

    @Test
    void adminPageListsOverrideHistoryWithoutAnyWritePath() throws Exception {
        setOverride("208001", 60_000, "第一版");
        ok(delete(BASE + "/my-prices/208001?regionCode=TEST_A").header("Authorization", "Bearer owner"));
        setOverride("208006", 100_000, null);
        var page = accounts.pageByAccount(1, 1, 20);
        assertThat(page.getTotal()).isEqualTo(2);
        assertThat(page.getList()).extracting(BudgetAccountPriceService.AdminAccountPrice::status)
                .containsExactlyInAnyOrder("ACTIVE", "REMOVED");
        assertThat(page.getList().get(0).optionLabel()).isNotBlank();
        assertThatThrownBy(() -> accounts.pageByAccount(1, 0, 20)).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> accounts.upsert(1, "TEST_A", 208001, Map.of("unitPriceCents", "abc")))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(BUDGET_ACCOUNT_PRICE_INVALID.getCode()));
    }

    private void setOverride(String optionId, long cents, String reason) {
        String body = reason == null ? "{\"unitPriceCents\":" + cents + "}" : "{\"unitPriceCents\":" + cents + ",\"reason\":\"" + reason + "\"}";
        try {
            var data = ok(put(BASE + "/my-prices/" + optionId + "?regionCode=TEST_A")
                    .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON).content(body));
            assertThat(data.path("myPriceCents").asLong()).isEqualTo(cents);
        } catch (Exception e) {
            throw new AssertionError("setOverride failed", e);
        }
    }

    private JsonNode group(JsonNode data, String itemCode) {
        for (JsonNode group : data.path("groups")) if (group.path("itemCode").asText().equals(itemCode)) return group;
        throw new AssertionError("Missing group " + itemCode + ": " + data);
    }

    private JsonNode option(JsonNode data, String optionId) {
        String container = data.has("groups") ? "groups" : "items";
        for (JsonNode group : data.path(container)) {
            for (JsonNode option : group.path("options")) if (option.path("optionId").asText().equals(optionId)) return option;
        }
        throw new AssertionError("Missing option " + optionId + ": " + data);
    }

    private JsonNode create(String key) {
        return tree(budgets.create(1, PROJECT, body(), key));
    }

    private JsonNode createForOther() {
        return tree(budgets.create(2, PROJECT + 1, otherBody(), "other-key"));
    }

    private Map<String, Object> body() {
        var result = new LinkedHashMap<String, Object>();
        result.put("resultVersionId", Long.toString(VERSION));
        result.put("inputOverrides", Map.of());
        result.put("optionIds", OPTIONS);
        return result;
    }

    private Map<String, Object> otherBody() {
        var result = new LinkedHashMap<String, Object>();
        result.put("resultVersionId", Long.toString(VERSION + 1));
        result.put("inputOverrides", Map.of());
        result.put("optionIds", OPTIONS);
        return result;
    }

    private Map<String, Object> inputs() {
        var result = new LinkedHashMap<String, Object>();
        result.put("regionCode", "TEST_A");
        result.put("footprintArea", "120");
        result.put("floorCount", 2);
        result.put("roofArea", "112");
        result.put("quantities", new LinkedHashMap<>(Map.of("DOOR_HOUSEHOLDS", "1", "CULTURE_STONE_LENGTH", "44",
                "LIGHTING_WASHER_LENGTH", "20", "LIGHTING_STRIP_LENGTH", "30", "WATERPROOF_AREA", "20")));
        return result;
    }

    private void project(long id, long userId, long requirementId, Map<String, Object> inputs) {
        jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (?, ?, 'SELF_UPLOAD')", id, userId);
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                        + "VALUES (?, ?, 1, CAST(? AS jsonb), '2026-01-01T00:00:00Z')", requirementId, id,
                tree(Map.of("budgetInputs", inputs, "prompt", "private fixture prompt")).toString());
    }

    private void version(long id, long projectId) {
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids, config_snapshot, create_time) "
                + "VALUES (?, ?, 1, 1, '[]', CAST('{}' AS jsonb), '2026-01-02T00:00:00Z')", id, projectId);
    }

    private void price(long id, long regionId, long optionId, long cents) {
        jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id, unit_price_cents, status, effective_at, published_by, published_at) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', '2020-01-01T00:00:00Z', 99, now())", id, regionId, optionId, cents);
    }

    private void custom(long itemId, long optionId) {
        jdbc.update("INSERT INTO budget_item (id, code, name, category, source, public_selectable, enabled, tenant_id) "
                + "VALUES (?, ?, '自定义排水附加项', 'EXTERIOR', 'CUSTOM_TEMPLATE', TRUE, TRUE, 0)", itemId, "CUSTOM_" + itemId);
        jdbc.update("INSERT INTO budget_option (id, item_id, code, label, selection_group, unit, quantity_source, enabled, tenant_id) "
                + "VALUES (?, ?, 'FIXED', '已核定排水套餐', 'CUSTOM', 'SET', 'FIXED_ONE', TRUE, 0)", optionId, itemId);
    }

    private long id(JsonNode budget) {
        return Long.parseLong(budget.path("budgetId").asText());
    }

    private int count(String fixtureTable) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fixtureTable, Integer.class);
    }

    private JsonNode tree(Object value) {
        try {
            return JSON.readTree(JSON.writeValueAsBytes(value));
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not serialize the test fixture to JSON", exception);
        }
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        String response = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(response);
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode response = response(request);
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data");
    }
}
