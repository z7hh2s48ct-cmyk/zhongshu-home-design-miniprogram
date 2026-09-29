package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.security.core.LoginUser;
import cn.iocoder.yudao.framework.security.core.util.SecurityFrameworkUtils;
import cn.iocoder.yudao.framework.tenant.core.context.TenantContextHolder;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.design.budget.AdminBudgetRevisionService;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.budget.ItemizedBudgetService;
import cn.iocoder.yudao.module.design.budget.BudgetQuoteService;
import cn.iocoder.yudao.module.design.controller.admin.AdminBudgetRevisionController;
import cn.iocoder.yudao.module.design.controller.admin.AdminBudgetQuoteController;
import cn.iocoder.yudao.module.design.controller.app.AppBudgetController;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import cn.iocoder.yudao.module.infra.zhongshu.api.AiJobPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.IdentitySessionPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import cn.iocoder.yudao.module.infra.zhongshu.audit.JdbcAuditPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10-07: disposable real PG + actual controller/method-security proxies and HTTP JSON binding.
 * Session/permission ports are controlled fixtures, not OAuth verification or deployed end-to-end testing.
 * The baseline and the 360,000-cent supplement are independent acceptance constants, never calculator-derived expectations.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetRevisionT10ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final long PROJECT = 9007199254741001L;
    private static final long VERSION = 9007199254742001L;
    private static final long REQUIREMENT = 9007199254743001L;
    private static final long TOTAL = 48_202_000L;
    private static final long SUPPLEMENTED = 48_562_000L;
    private static final String ADMIN = "/design/v1/budget/estimates";
    private static final String APP = "/design/v1";
    private static final List<String> OPTIONS = List.of("208001", "208006", "208009", "208012", "208015", "208020",
            "208023", "208026", "208029", "208030", "208032", "208034", "208035");
    private static final long[] PRICES = {52_000, 98_000, 52_000, 12_000, 1_800_000, 49_000,
            12_000, 12_000, 12_000, 6_000, 8_500, 500_000, 500_000};
    private SimpleDriverDataSource ds;
    private DataSourceTransactionManager transactions;
    private JdbcTemplate jdbc;
    private ItemizedBudgetService appBudgets;
    private AdminBudgetRevisionService revisions;
    private BudgetService legacy;
    private BudgetQuoteService quotes;
    private MockMvc mvc;
    private AnnotationConfigApplicationContext context;
    private final PermissionProbe permissions = new PermissionProbe();
    private String budgetId;
    private String initialRevisionId;
    private JsonNode initialPublic;
    private List<Map<String, Object>> itemNames;
    private List<Map<String, Object>> optionLabels;

    @BeforeAll
    void migrate() {
        ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        transactions = new DataSourceTransactionManager(ds);
        jdbc = new JdbcTemplate(ds);
        Flyway.configure().dataSource(ds).locations("classpath:db/migration/platform", "classpath:db/migration/design").load().migrate();
        itemNames = jdbc.queryForList("SELECT id, name FROM budget_item");
        optionLabels = jdbc.queryForList("SELECT id, label FROM budget_option");
        var projects = new DesignProjectService(ds, transactions, mock(AiJobPort.class), null, null, null);
        appBudgets = new ItemizedBudgetService(ds, transactions, projects, new JdbcAuditPort(ds));
        legacy = new BudgetService(ds, projects);
        revisions = service(new JdbcAuditPort(ds));
        quotes = new BudgetQuoteService(ds, transactions, new JdbcAuditPort(ds));
        context = new AnnotationConfigApplicationContext();
        context.register(SecurityConfiguration.class);
        context.registerBean("ss", PermissionProbe.class, () -> permissions);
        context.registerBean(AdminBudgetRevisionService.class, () -> revisions);
        context.registerBean(BudgetQuoteService.class, () -> quotes);
        context.registerBean(AdminBudgetRevisionController.class);
        context.registerBean(AdminBudgetQuoteController.class);
        context.refresh();
        var app = new AppBudgetController();
        ReflectionTestUtils.setField(app, "itemizedBudgetService", appBudgets);
        ReflectionTestUtils.setField(app, "budgetService", legacy);
        ReflectionTestUtils.setField(app, "designProjectService", projects);
        ReflectionTestUtils.setField(app, "budgetQuoteService", quotes);
        IdentitySessionPort identities = token -> switch (token == null ? "" : token) {
            case "owner" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "owner", false));
            case "other" -> Optional.of(new IdentitySessionPort.SessionContext(2, "test", "other", false));
            case "restricted" -> Optional.of(new IdentitySessionPort.SessionContext(1, "test", "owner", true));
            default -> Optional.empty();
        };
        ReflectionTestUtils.setField(app, "identitySessionPort", identities);
        mvc = MockMvcBuilders.standaloneSetup(context.getBean(AdminBudgetRevisionController.class), context.getBean(AdminBudgetQuoteController.class), app)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .setControllerAdvice(new GlobalExceptionHandler("t10-revision-contract", mock(ApiErrorLogCommonApi.class))).build();
    }

    @BeforeEach
    void seedOnlyThisDisposableDatabase() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
        jdbc.execute("TRUNCATE budget_quote, budget_line, budget_revision, budget_estimate, budget_item_price, budget_region, "
                + "budget_app_command, budget_catalog_command, budget_rule_version, design_project, design_requirement_snapshot, design_result_version, audit_event");
        jdbc.update("DELETE FROM budget_option WHERE item_id IN (SELECT id FROM budget_item WHERE source = 'CUSTOM_TEMPLATE')");
        jdbc.update("DELETE FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'");
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE, deleted = FALSE");
        jdbc.update("UPDATE budget_option SET enabled = TRUE, deleted = FALSE");
        itemNames.forEach(row -> jdbc.update("UPDATE budget_item SET name = ? WHERE id = ?", row.get("name"), row.get("id")));
        optionLabels.forEach(row -> jdbc.update("UPDATE budget_option SET label = ? WHERE id = ?", row.get("label"), row.get("id")));
        jdbc.update("INSERT INTO budget_region (id, code, name, enabled) VALUES (810001, 'TEST_A', '金样测试地区', TRUE), (810002, 'TEST_B', '另一地区', TRUE)");
        for (int i = 0; i < OPTIONS.size(); i++) price(820001L + i, 810001, Long.parseLong(OPTIONS.get(i)), PRICES[i]);
        project(PROJECT, 1, REQUIREMENT);
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids, config_snapshot, create_time) "
                + "VALUES (?, ?, 1, 1, '[]', '{}', '2026-01-02T00:00:00Z')", VERSION, PROJECT);
        initialPublic = tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "initial"));
        budgetId = initialPublic.path("budgetId").asText();
        initialRevisionId = initialPublic.path("revisionId").asText();
        permissions.allowed = Set.of("design:budget:query", "design:budget:edit", "design:budget:configure", "design:budget:quote", "design:budget:quote-publish");
        login(99, 1, null);
    }

    @AfterEach
    void clearSessions() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterAll
    void closeContext() { if (context != null) context.close(); }

    @Test
    void projectDrainageAddsExactly360000CentsInNewRevisionAndLeavesInitialRowsUntouched() throws Exception {
        var originalRevision = revisionRows(1);
        var originalLines = lineRows(1);
        JsonNode detail = ok(postRevision(budgetId, "drainage", add(1, drainage("drainage", "20", 18_000L))));
        assertThat(detail.path("budgetId").asText()).isEqualTo(budgetId);
        assertThat(detail.path("revisionNo").asInt()).isEqualTo(2);
        assertThat(detail.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(detail.path("totalCents").asLong()).isEqualTo(SUPPLEMENTED);
        assertThat(detail.path("categoryTotals").path("EXTERIOR").asLong()).isEqualTo(12_978_000L);
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(jdbc.queryForObject("SELECT body_subtotal_cents FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isEqualTo(35_584_000L);
        assertThat(jdbc.queryForObject("SELECT exterior_subtotal_cents FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isEqualTo(12_978_000L);
        assertThat(currentLines()).hasSize(14);
        Map<String, Object> custom = currentCustom("DRAINAGE");
        assertThat(custom).containsEntry("source", "PROJECT_CUSTOM").containsEntry("unit", "METER").containsEntry("amount_cents", 360_000L);
        assertThat(custom.get("item_id")).isNull();
        assertThat(custom.get("option_id")).isNull();
        assertThat(custom.get("price_version_id")).isNull();
        assertThat(revisionRows(1)).isEqualTo(originalRevision);
        assertThat(lineRows(1)).isEqualTo(originalLines);
        assertThat(count("budget_estimate")).isEqualTo(1);
        assertThat(count("budget_catalog_command")).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT actor_id FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isEqualTo(99L);
    }

    @Test
    void quoteKeepsCalculatedTotalImmutableAndOnlyBecomesOwnerVisibleAfterExplicitPublication() throws Exception {
        JsonNode complete = revise("quote-golden-drainage", add(1, drainage("quote-golden-drainage", "20", 18_000L)));
        String revisionId = complete.path("revisionId").asText();
        var request = Map.<String, Object>of("revisionId", revisionId, "expectedVersion", 2,
                "adjustmentCents", -562_000L, "reason", "客户确认的整单优惠");
        var draft = quotes.create(99, budgetId, "quote-draft", request);
        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.calculatedTotalCents()).isEqualTo(SUPPLEMENTED);
        assertThat(draft.adjustmentCents()).isEqualTo(-562_000L);
        assertThat(draft.finalPriceCents()).isEqualTo(48_000_000L);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM budget_revision WHERE id = ?", Long.class, Long.parseLong(revisionId))).isEqualTo(SUPPLEMENTED);
        assertThat(ok(get(APP + "/design-projects/{projectId}/budget-quotes", PROJECT).header("Authorization", "Bearer owner")).size()).isZero();

        var published = quotes.publish(99, draft.quoteId(), "quote-publish", Map.of("expectedVersion", 1, "reason", "复核通过并正式发布"));
        assertThat(published.status()).isEqualTo("PUBLISHED");
        assertThat(published.currentPublic()).isTrue();
        JsonNode publicRows = ok(get(APP + "/design-projects/{projectId}/budget-quotes", PROJECT).header("Authorization", "Bearer owner"));
        assertThat(publicRows.size()).isEqualTo(1);
        JsonNode visible = publicRows.get(0);
        assertThat(visible.path("current").asBoolean()).isTrue();
        assertThat(visible.path("finalPriceCents").asLong()).isEqualTo(48_000_000L);
        assertThat(visible.toString()).doesNotContain("客户确认", "reason", "unitPriceCents", "internalNote", "sourceReference");
        assertThat(quotes.create(99, budgetId, "quote-draft", request)).isEqualTo(draft);
        rejected(IDEMPOTENCY_KEY_REUSED, () -> quotes.create(99, budgetId, "quote-draft",
                Map.of("revisionId", revisionId, "expectedVersion", 2, "finalPriceCents", SUPPLEMENTED, "reason", "另一请求")));
    }

    @Test
    void staleDraftCannotPublishAndWithdrawalNeverFallsBackToOlderPublishedQuote() throws Exception {
        var first = quotes.create(99, budgetId, "quote-v1", Map.of("revisionId", initialRevisionId, "expectedVersion", 1,
                "finalPriceCents", TOTAL, "reason", "首版报价"));
        quotes.publish(99, first.quoteId(), "publish-v1", Map.of("expectedVersion", 1, "reason", "发布首版"));
        var stale = quotes.create(99, budgetId, "quote-v2", Map.of("revisionId", initialRevisionId, "expectedVersion", 1,
                "finalPriceCents", TOTAL + 1_000L, "reason", "待发布二版"));
        JsonNode revision = revise("quote-budget-revision", add(1, drainage("quote-drainage", "20", 18_000L)));
        var latest = quotes.create(99, budgetId, "quote-v3", Map.of("revisionId", revision.path("revisionId").asText(), "expectedVersion", 2,
                "finalPriceCents", SUPPLEMENTED + 2_000L, "reason", "按最新预算修订形成三版"));
        assertThat(quotes.adminGet(stale.quoteId()).stale()).isTrue();
        rejected(STATE_VERSION_CONFLICT, () -> quotes.publish(99, stale.quoteId(), "publish-stale", Map.of("expectedVersion", 1, "reason", "错误发布旧草稿")));
        var published = quotes.publish(99, latest.quoteId(), "publish-v3", Map.of("expectedVersion", 1, "reason", "发布最新版本"));
        assertThat(published.currentPublic()).isTrue();
        var withdrawn = quotes.withdraw(99, latest.quoteId(), "withdraw-v3", Map.of("expectedVersion", 2, "reason", "客户方案变更暂时撤回"));
        assertThat(withdrawn.status()).isEqualTo("WITHDRAWN");
        assertThat(withdrawn.currentPublic()).isFalse();
        assertThat(quotes.adminGet(first.quoteId()).currentPublic()).isFalse();
        JsonNode history = ok(get(APP + "/design-projects/{projectId}/budget-quotes", PROJECT).header("Authorization", "Bearer owner"));
        assertThat(history.get(0).path("status").asText()).isEqualTo("WITHDRAWN");
        assertThat(history.get(0).path("current").asBoolean()).isTrue();
        assertThat(history.get(1).path("current").asBoolean()).isFalse();
        assertThat(response(get(APP + "/design-projects/{projectId}/budget-quotes/{quoteId}", PROJECT, latest.quoteId()).header("Authorization", "Bearer other")).path("code").asInt()).isEqualTo(RESOURCE_FORBIDDEN.getCode());
    }

    @Test
    void incompleteOrStaleBudgetAndInvalidMoneyCannotCreateFinalQuote() {
        revise("missing-price-before-quote", add(1, drainage("missing", "20", null)));
        String incompleteRevision = String.valueOf(currentRevision());
        rejected(BUDGET_INCOMPLETE, () -> quotes.create(99, budgetId, "incomplete", Map.of("revisionId", incompleteRevision,
                "expectedVersion", 2, "finalPriceCents", TOTAL, "reason", "不能发布待补预算")));
        rejected(STATE_VERSION_CONFLICT, () -> quotes.create(99, budgetId, "old-version", Map.of("revisionId", initialRevisionId,
                "expectedVersion", 1, "finalPriceCents", TOTAL, "reason", "旧版本")));
        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("revisionId", incompleteRevision, "expectedVersion", 2, "finalPriceCents", TOTAL, "adjustmentCents", 0, "reason", "二选一冲突"),
                Map.of("revisionId", incompleteRevision, "expectedVersion", 2, "adjustmentCents", -10_000_000_001L, "reason", "负价越界"),
                Map.of("revisionId", incompleteRevision, "expectedVersion", 2, "finalPriceCents", 1.5d, "reason", "浮点非法"))) {
            rejected(BUDGET_INPUT_INVALID, () -> quotes.create(99, budgetId, UUID.randomUUID().toString(), body));
        }
    }

    @Test
    void missingPriceThenExplicitSupplementPreservesMissingHistoryAndOriginalValues() {
        JsonNode missing = revise("unknown-price", add(1, drainage("drainage", "20", null)));
        String missingRevision = missing.path("revisionId").asText();
        assertRevision(2, "INCOMPLETE", TOTAL, null);
        assertThat(currentCustom("DRAINAGE")).containsEntry("status", "MISSING_PRICE").containsEntry("unit_price_cents", null).containsEntry("amount_cents", null);
        assertThat(missing.path("totalCents").isNull()).isTrue();
        var before = lineRows(2);
        JsonNode complete = revise("price-confirmed", update(2, Map.of("lineId", customLineId(), "unitPriceCents", 18_000)));
        assertRevision(3, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(lineRows(2)).isEqualTo(before);
        JsonNode old = tree(revisions.get(budgetId, missingRevision));
        assertThat(old.path("readOnly").asBoolean()).isTrue();
        assertThat(old.path("totalCents").isNull()).isTrue();
        assertThat(findLine(complete, "DRAINAGE").path("originalUnitPriceCents").isNull()).isTrue();
        assertThat(findLine(complete, "DRAINAGE").path("unitPriceCents").asLong()).isEqualTo(18_000L);
    }

    @Test
    void missingBothAndMissingQuantityStayIncompleteUntilBothAreKnown() {
        revise("unknown-both", add(1, drainage("drainage", null, null)));
        assertRevision(2, "INCOMPLETE", TOTAL, null);
        assertThat(currentCustom("DRAINAGE").get("status")).isEqualTo("MISSING_BOTH");
        revise("price-only", update(2, Map.of("lineId", customLineId(), "unitPriceCents", 18_000)));
        assertRevision(3, "INCOMPLETE", TOTAL, null);
        assertThat(currentCustom("DRAINAGE").get("status")).isEqualTo("MISSING_QUANTITY");
        revise("quantity-known", update(3, Map.of("lineId", customLineId(), "quantity", "20")));
        assertRevision(4, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
    }

    @Test
    void zeroRequiresExplicitFreeReasonAndExclusionIsNotAnUnexplainedZero() {
        var zero = drainage("free", "20", 0L);
        rejected(BUDGET_INPUT_INVALID, () -> revise("zero-no-reason", add(1, zero)));
        zero.put("freeReason", "内部核定免费赠送");
        revise("explicit-free", add(1, zero));
        assertRevision(2, "COMPLETE", TOTAL, TOTAL);
        assertThat(currentCustom("DRAINAGE")).containsEntry("status", "PRICED").containsEntry("amount_cents", 0L);
        var excluded = new LinkedHashMap<String, Object>();
        excluded.put("lineId", customLineId());
        excluded.put("unitPriceCents", null);
        excluded.put("freeReason", null);
        excluded.put("excludedReason", "本修订明确不含此施工");
        revise("explicit-exclusion", update(2, excluded));
        assertRevision(3, "COMPLETE", TOTAL, TOTAL);
        assertThat(currentCustom("DRAINAGE")).containsEntry("status", "EXCLUDED").containsEntry("amount_cents", null);
        var noReason = new LinkedHashMap<>(excluded);
        noReason.put("lineId", customLineId());
        noReason.put("excludedReason", " ");
        rejected(BUDGET_INPUT_INVALID, () -> revise("blank-exclusion", update(3, noReason)));
    }

    @Test
    void nullClearsAnExistingValueWhileOmittedFieldsAndStableLineKeysRemainFrozen() {
        revise("initial-drain", add(1, drainage("drainage", "20", 18_000L)));
        String oldLine = customLineId();
        String key = (String) currentCustom("DRAINAGE").get("line_key");
        var change = new LinkedHashMap<String, Object>();
        change.put("lineId", oldLine);
        change.put("quantity", null);
        revise("clear-quantity", update(2, change));
        assertRevision(3, "INCOMPLETE", TOTAL, null);
        var current = currentCustom("DRAINAGE");
        assertThat(current).containsEntry("quantity", null).containsEntry("unit_price_cents", 18_000L).containsEntry("line_key", key);
        assertThat(current.get("id").toString()).isNotEqualTo(oldLine);
        assertThat(lineRows(2)).anySatisfy(row -> assertThat(row).containsEntry("id", Long.parseLong(oldLine)).containsEntry("amount_cents", 360_000L));
        rejected(RESOURCE_FORBIDDEN, () -> revise("stale-line", update(3, Map.of("lineId", oldLine, "quantity", "20"))));
    }

    @Test
    void sameNameProjectItemsStayIndependentAndRemovingOneCreatesAnotherImmutableVersion() {
        var one = drainage("drain-a", "20", 18_000L);
        var two = drainage("drain-b", "10", 18_000L);
        revise("same-name", patch(1, List.of(), List.of(one, two), List.of()));
        assertRevision(2, "COMPLETE", 48_742_000L, 48_742_000L);
        var copies = jdbc.queryForList("SELECT id::text AS id, line_key, amount_cents FROM budget_line WHERE revision_id = ? AND source = 'PROJECT_CUSTOM' ORDER BY amount_cents DESC", currentRevision());
        assertThat(copies).hasSize(2);
        assertThat(copies).extracting(row -> row.get("line_key")).doesNotHaveDuplicates();
        var old = lineRows(2);
        revise("remove-one", patch(2, List.of(), List.of(), List.of((String) copies.get(1).get("id"))));
        assertRevision(3, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(lineRows(2)).isEqualTo(old);
        assertThat(currentLines()).hasSize(14);
    }

    @Test
    void fixedTemporaryChargesRequireItemTimesOneAndFixedCatalogRulesCannotBecomeTwo() {
        var fixed = drainage("fixed", "1", 360_000L);
        fixed.put("unit", "ITEM");
        revise("fixed-one", add(1, fixed));
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        rejected(BUDGET_INPUT_INVALID, () -> revise("fixed-two", update(2, Map.of("lineId", customLineId(), "quantity", "2"))));
        rejected(BUDGET_INPUT_INVALID, () -> revise("insurance-two", update(2, Map.of("lineId", optionLineId(208035), "quantity", "2"))));
        var empty = new LinkedHashMap<String, Object>();
        empty.put("lineId", customLineId()); empty.put("quantity", null);
        revise("fixed-explicit-null", update(2, empty));
        assertRevision(3, "INCOMPLETE", TOTAL, null);
    }

    @Test
    void professionalQuantityCanBeSupplementedWithoutGuessingOrChangingTheOriginalProject() {
        var inputs = inputs();
        @SuppressWarnings("unchecked") var quantities = new LinkedHashMap<>((Map<String, Object>) inputs.get("quantities"));
        quantities.remove("CULTURE_STONE_LENGTH"); inputs.put("quantities", quantities);
        replaceInputs(inputs);
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "missing-stone")));
        String projectBefore = jdbc.queryForObject("SELECT inputs::text FROM design_requirement_snapshot WHERE id = ?", String.class, REQUIREMENT);
        assertRevision(1, "INCOMPLETE", 47_674_000L, null);
        revise("measure-stone", update(1, Map.of("lineId", optionLineId(208026), "quantity", "44")));
        assertRevision(2, "COMPLETE", TOTAL, TOTAL);
        assertThat(jdbc.queryForObject("SELECT inputs::text FROM design_requirement_snapshot WHERE id = ?", String.class, REQUIREMENT)).isEqualTo(projectBefore);
    }

    @Test
    void globalMissingRoofAndActualAreaReviewCannotBeErasedByPatchingOneLineQuantity() {
        var missing = inputs(); missing.remove("roofArea"); replaceInputs(missing);
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "global-missing-roof")));
        JsonNode patched = revise("roof-line-only", update(1, Map.of("lineId", optionLineId(208009), "quantity", "112")));
        assertRevision(2, "INCOMPLETE", TOTAL, null);
        assertThat(patched.path("missingFields").toString()).contains("roofArea");
        var actual = inputs(); actual.put("buildingArea", "251.2"); replaceInputs(actual);
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "actual-review")));
        JsonNode reviewed = revise("cannot-clear-review", add(1, drainage("drainage", "20", 18_000L)));
        assertRevision(2, "INCOMPLETE", 50_065_600L, null);
        assertThat(reviewed.path("warnings").toString()).contains("BUILDING_AREA_REVIEW_REQUIRED");
        var spoof = add(2, drainage("another", "1", 100L));
        spoof.put("inputSnapshot", Map.of("warnings", List.of(), "missingFields", List.of()));
        rejected(BUDGET_INPUT_INVALID, () -> revise("spoof-inputs", spoof));
    }

    @Test
    void missingStandardSelectionCanOnlyBeFilledBySameParentAndRequiredGroup() {
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS.stream().filter(id -> !id.equals("208020")).toList(), Long.toString(VERSION)), "missing-window")));
        String placeholder = jdbc.queryForObject("SELECT id::text FROM budget_line WHERE revision_id = ? AND item_code = 'DOORS_WINDOWS' AND option_id IS NULL", String.class, currentRevision());
        rejected(BUDGET_INPUT_INVALID, () -> revise("wrong-group", update(1, Map.of("lineId", placeholder, "optionId", "208029"))));
        rejected(BUDGET_INPUT_INVALID, () -> revise("same-parent-wrong-group", update(1, Map.of("lineId", placeholder, "optionId", "208016"))));
        revise("fill-window", update(1, Map.of("lineId", placeholder, "optionId", "208020")));
        assertRevision(2, "COMPLETE", TOTAL, TOTAL);
        assertThat(currentLines()).hasSize(13);
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM budget_line WHERE revision_id = ? AND option_id = 208020", Long.class, currentRevision())).isEqualTo(2_940_000L);
        rejected(BUDGET_INPUT_INVALID, () -> revise("replace-priced-standard", update(2, Map.of("lineId", optionLineId(208020), "optionId", "208019"))));
    }

    @Test
    void ordinaryItemPlaceholderCanSelectItsRealFoundationGroupAndClientKeysAllowExactly64Characters() {
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS.stream().filter(id -> !id.equals("208001")).toList(), Long.toString(VERSION)), "missing-foundation")));
        String placeholder = jdbc.queryForObject("SELECT id::text FROM budget_line WHERE revision_id = ? AND item_code = 'FOUNDATION' AND option_id IS NULL", String.class, currentRevision());
        assertThat(findLine(tree(revisions.get(budgetId, null)), "FOUNDATION").path("selectionGroup").asText()).isEqualTo("ITEM");
        revise("select-foundation", update(1, Map.of("lineId", placeholder, "optionId", "208001")));
        assertRevision(2, "COMPLETE", TOTAL, TOTAL);
        assertThat(findLine(tree(revisions.get(budgetId, null)), "FOUNDATION").path("selectionGroup").asText()).isEqualTo("FOUNDATION");
        String key = "a".repeat(64);
        revise("client-key-64", add(2, drainage(key, "20", 18_000L)));
        assertRevision(3, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(currentCustom("DRAINAGE").get("line_key").toString()).hasSizeLessThanOrEqualTo(64);
        assertThat(jdbc.queryForObject("SELECT pricing_snapshot->>'clientKey' FROM budget_line WHERE id = ?", String.class, Long.parseLong(customLineId()))).isEqualTo(key);
        rejected(BUDGET_INPUT_INVALID, () -> revise("client-key-65", add(3, drainage("a".repeat(65), "20", 18_000L))));
    }

    @Test
    void standardLinesAndMissingSelectionGroupsCannotBeRemovedToFakeCompleteness() {
        rejected(BUDGET_INPUT_INVALID, () -> revise("remove-standard", patch(1, List.of(), List.of(), List.of(optionLineId(208001)))));
        selectBudget(tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS.stream().filter(id -> !id.equals("208020")).toList(), Long.toString(VERSION)), "missing-window")));
        String placeholder = jdbc.queryForObject("SELECT id::text FROM budget_line WHERE revision_id = ? AND item_code = 'DOORS_WINDOWS' AND option_id IS NULL", String.class, currentRevision());
        rejected(BUDGET_INPUT_INVALID, () -> revise("remove-placeholder", patch(1, List.of(), List.of(), List.of(placeholder))));
        assertRevision(1, "INCOMPLETE", 45_262_000L, null);
        revise("exclude-window-with-reason", update(1, Map.of("lineId", placeholder, "excludedReason", "业主明确本次不含窗")));
        assertRevision(2, "COMPLETE", 45_262_000L, 45_262_000L);
        assertThat(currentLines()).hasSize(13);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_line WHERE revision_id = ? AND item_code = 'DOORS_WINDOWS' AND option_id IS NULL", String.class, currentRevision())).isEqualTo("EXCLUDED");
    }

    @Test
    void unchangedRowsNeverRepriceFromCatalogAndProjectSupplementsNeverWriteBackToTemplates() {
        var original = frozenLineValues(1);
        jdbc.update("UPDATE budget_item_price SET unit_price_cents = 1");
        jdbc.update("UPDATE budget_item SET name = '新目录名' WHERE id = 207001");
        jdbc.update("UPDATE budget_option SET label = '新选项名' WHERE id = 208001");
        var changed = inputs(); changed.put("footprintArea", "130"); replaceInputs(changed);
        revise("freeze-old-lines", add(1, drainage("drainage", "20", 18_000L)));
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(frozenLineValues(2)).containsExactlyInAnyOrderElementsOf(original);
        assertThat(tree(revisions.get(budgetId, initialRevisionId)).path("totalCents").asLong()).isEqualTo(TOTAL);
        assertThat(currentLines()).anySatisfy(row -> assertThat(row).containsEntry("option_id", 208001L).containsEntry("public_name", "地基基础").containsEntry("unit_price_cents", 52_000L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'", Integer.class)).isZero();
    }

    @Test
    void administratorCanAddPrivateEnabledTemplateButNotAStandardOptionOrDisabledOrForeignTenantOption() {
        catalogTemplate(830001, 830002, 0);
        price(830003, 810001, 830002, 360_000L);
        var addition = new LinkedHashMap<String, Object>(Map.of("kind", "CATALOG_OPTION", "clientKey", "private-template", "optionId", "830002"));
        revise("private-template", add(1, addition));
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(currentLines()).anySatisfy(row -> assertThat(row).containsEntry("source", "CUSTOM_TEMPLATE").containsEntry("option_id", 830002L));
        rejected(BUDGET_INPUT_INVALID, () -> revise("add-standard", add(2, Map.of("kind", "CATALOG_OPTION", "clientKey", "standard", "optionId", "208002"))));
        jdbc.update("UPDATE budget_option SET enabled = FALSE WHERE id = 830002");
        rejected(BUDGET_INPUT_INVALID, () -> revise("disabled-template", add(2, addition)));
        catalogTemplate(830011, 830012, 1);
        rejected(BUDGET_INPUT_INVALID, () -> revise("foreign-template", add(2, Map.of("kind", "CATALOG_OPTION", "clientKey", "foreign", "optionId", "830012"))));
    }

    @Test
    void templateMissingRegionalPriceStaysMissingUntilAnExplicitProjectPriceIsProvided() {
        catalogTemplate(830001, 830002, 0);
        price(830003, 810002, 830002, 360_000L);
        revise("template-no-local-price", add(1, Map.of("kind", "CATALOG_OPTION", "clientKey", "private-template", "optionId", "830002")));
        assertRevision(2, "INCOMPLETE", TOTAL, null);
        assertThat(jdbc.queryForObject("SELECT status FROM budget_line WHERE revision_id = ? AND option_id = 830002", String.class, currentRevision())).isEqualTo("MISSING_PRICE");
        revise("project-price", update(2, Map.of("lineId", optionLineId(830002), "unitPriceCents", 360_000)));
        assertRevision(3, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item_price WHERE option_id = 830002 AND region_id = 810001", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_item_price WHERE id = 830003", Long.class)).isEqualTo(360_000L);
    }

    @Test
    void anotherProjectAndAllAppReadPathsRetainTheirOwnOriginalSnapshot() throws Exception {
        project(PROJECT + 10, 2, REQUIREMENT + 10);
        var other = tree(appBudgets.create(2, PROJECT + 10, initialRequest(OPTIONS, null), "other-project"));
        revise("admin-secret", add(1, drainage("drainage", "20", 18_000L)));
        String adminRevision = Long.toString(currentRevision());
        assertThat(tree(appBudgets.get(2, Long.parseLong(other.path("budgetId").asText()), null).orElseThrow())).isEqualTo(other);
        JsonNode detail = ok(get(APP + "/budget-estimates/{id}", budgetId).header("Authorization", "Bearer owner"));
        assertThat(detail).isEqualTo(initialPublic);
        JsonNode list = ok(get(APP + "/design-projects/{id}/budget-estimates", PROJECT).header("Authorization", "Bearer owner"));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0)).isEqualTo(initialPublic);
        assertThat(response(get(APP + "/budget-estimates/{id}", budgetId).param("revisionId", adminRevision)
                .header("Authorization", "Bearer owner")).path("code").asInt()).isEqualTo(RESOURCE_FORBIDDEN.getCode());
        ok(post(APP + "/budget-estimates/{id}/save", budgetId).header("Authorization", "Bearer owner").header("Idempotency-Key", "save-owner"));
        JsonNode saved = ok(get(APP + "/budget-estimates/{id}", budgetId).header("Authorization", "Bearer owner"));
        assertThat(saved.path("revisionId").asText()).isEqualTo(initialRevisionId);
        assertThat(saved.path("totalCents").asLong()).isEqualTo(TOTAL);
        assertThat(saved.toString()).doesNotContain("排水沟", "内部专用", "unitPriceCents", "internalNote", "freeReason", "excludedReason");
        for (String token : List.of("other", "restricted")) {
            assertThat(response(get(APP + "/budget-estimates/{id}", budgetId).header("Authorization", "Bearer " + token)).path("code").asInt()).isNotZero();
        }
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
    }

    @Test
    void sameKeyReplaysOriginalRevisionEvenAfterNewerRevisionButChangedBodyOrBudgetConflicts() {
        var request = add(1, drainage("drainage", "20", 18_000L));
        JsonNode first = revise("replay", request);
        revise("subsequent", update(2, Map.of("lineId", customLineId(), "quantity", "21")));
        assertThat(revise("replay", request)).isEqualTo(first);
        rejected(IDEMPOTENCY_KEY_REUSED, () -> revise("replay", add(1, drainage("drainage", "21", 18_000L))));
        String another = tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "another-budget")).path("budgetId").asText();
        rejected(IDEMPOTENCY_KEY_REUSED, () -> revisions.revise(99, another, "replay", request));
        assertRevision(3, "COMPLETE", 48_580_000L, 48_580_000L);
        assertThat(count("budget_catalog_command")).isEqualTo(2);
    }

    @Test
    void omittedAndExplicitNullUpdatesHaveDifferentIdempotencyFingerprints() {
        String foundation = optionLineId(208001);
        var omitted = update(1, Map.of("lineId", foundation, "internalNote", "核对未改金额"));
        JsonNode one = revise("null-semantics", omitted);
        var explicit = new LinkedHashMap<>(Map.<String, Object>of("lineId", foundation, "internalNote", "核对未改金额"));
        explicit.put("unitPriceCents", null);
        rejected(IDEMPOTENCY_KEY_REUSED, () -> revise("null-semantics", update(1, explicit)));
        assertThat(revise("null-semantics", omitted)).isEqualTo(one);
        explicit.put("lineId", optionLineId(208001));
        revise("clear-price", update(2, explicit));
        assertRevision(3, "INCOMPLETE", 41_962_000L, null);
    }

    @Test
    void concurrentReplayProducesOnlyOneNewRevisionAndTwoAdministratorsCannotOverwriteEachOther() throws Exception {
        var same = add(1, drainage("drainage", "20", 18_000L));
        List<Object> replayed = race(() -> revisions.revise(99, budgetId, "parallel-same", same),
                () -> revisions.revise(99, budgetId, "parallel-same", same));
        assertThat(tree(replayed.get(0))).isEqualTo(tree(replayed.get(1)));
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
        assertThat(count("budget_catalog_command")).isEqualTo(1);
        String line = customLineId();
        List<Object> competing = race(() -> revisionCode(99, "competing-a", update(2, Map.of("lineId", line, "quantity", "21"))),
                () -> revisionCode(100, "competing-b", update(2, Map.of("lineId", line, "quantity", "22"))));
        assertThat(competing).containsExactlyInAnyOrder(0, STATE_VERSION_CONFLICT.getCode());
        assertThat(currentVersion()).isEqualTo(3);
        assertThat(count("budget_catalog_command")).isEqualTo(2);
        assertThat(count("audit_event")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT total_cents FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isIn(48_580_000L, 48_598_000L);
    }

    @Test
    void auditFailureRollsBackNewRevisionLinesCurrentPointerAndReceiptThenAllowsRetry() {
        var state = databaseState();
        var failing = service(message -> { new JdbcAuditPort(ds).record(message); throw new IllegalStateException("audit failed after insert"); });
        var request = add(1, drainage("drainage", "20", 18_000L));
        assertThatThrownBy(() -> failing.revise(99, budgetId, "audit-rollback", request)).isInstanceOf(IllegalStateException.class);
        assertThat(databaseState()).isEqualTo(state);
        revise("audit-rollback", request);
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
    }

    @Test
    void lineOrTotalFailureCannotCommitAnyPartOfTheNewRevision() {
        var state = databaseState();
        jdbc.execute("ALTER TABLE budget_line ADD CONSTRAINT ck_t10_revision_test_failure CHECK (item_code <> 'DRAINAGE')");
        try {
            assertThatThrownBy(() -> revise("line-rollback", add(1, drainage("drainage", "20", 18_000L)))).isInstanceOf(RuntimeException.class);
            assertThat(databaseState()).isEqualTo(state);
        } finally { jdbc.execute("ALTER TABLE budget_line DROP CONSTRAINT ck_t10_revision_test_failure"); }
        rejected(BUDGET_AMOUNT_LIMIT, () -> revise("amount-overflow", add(1, drainage("drainage", "1000000", 100_000_000L))));
        assertThat(databaseState()).isEqualTo(state);
        revise("line-rollback", add(1, drainage("drainage", "20", 18_000L)));
        assertRevision(2, "COMPLETE", SUPPLEMENTED, SUPPLEMENTED);
    }

    @Test
    void malformedQuantitiesMoneyIdentifiersAndSpoofedFieldsFailWithoutWrites() {
        var state = databaseState();
        for (Object quantity : List.of("0", "-1", "1.00001", "1000000.0001", "1e2", 20)) {
            var line = drainage("invalid", "20", 18_000L); line.put("quantity", quantity);
            rejected(BUDGET_INPUT_INVALID, () -> revise("bad-quantity", add(1, line)));
        }
        for (Object cents : List.of(-1, 1.5, "18000", 100_000_001L)) {
            var line = drainage("invalid", "20", 18_000L); line.put("unitPriceCents", cents);
            rejected(BUDGET_INPUT_INVALID, () -> revise("bad-money", add(1, line)));
        }
        var fractionalCount = drainage("invalid", "1.5", 100L); fractionalCount.put("unit", "PIECE");
        rejected(BUDGET_INPUT_INVALID, () -> revise("fractional-count", add(1, fractionalCount)));
        for (String field : List.of("amountCents", "totalCents", "actorId", "tenantId", "source", "itemId")) {
            var line = drainage("invalid", "20", 18_000L); line.put(field, 1);
            rejected(BUDGET_INPUT_INVALID, () -> revise("spoof-line-" + field, add(1, line)));
        }
        var request = add(1, drainage("invalid", "20", 18_000L)); request.put("totalCents", 1);
        rejected(BUDGET_INPUT_INVALID, () -> revise("spoof-total", request));
        request.remove("totalCents"); request.put("reason", " ");
        rejected(BUDGET_INPUT_INVALID, () -> revise("blank-reason", request));
        for (Object version : List.of(0, 1.5, "1")) {
            var bad = add(1, drainage("invalid", "20", 18_000L)); bad.put("expectedVersion", version);
            rejected(BUDGET_INPUT_INVALID, () -> revise("bad-version", bad));
        }
        rejected(BUDGET_INPUT_INVALID, () -> revisions.get("9223372036854775808", null));
        assertThat(databaseState()).isEqualTo(state);
    }

    @Test
    void duplicateClientKeysDuplicateUpdatesAndCrossBudgetLinesCannotCreateAmbiguousCharges() {
        var state = databaseState();
        var duplicate = drainage("same-key", "20", 18_000L);
        rejected(BUDGET_INPUT_INVALID, () -> revise("duplicate-add", patch(1, List.of(), List.of(duplicate, duplicate), List.of())));
        var one = Map.<String, Object>of("lineId", optionLineId(208001), "quantity", "120");
        rejected(BUDGET_INPUT_INVALID, () -> revise("duplicate-update", patch(1, List.of(one, one), List.of(), List.of())));
        assertThat(databaseState()).isEqualTo(state);
        String foreign = tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "foreign-budget")).path("budgetId").asText();
        String foreignLine = jdbc.queryForObject("SELECT l.id::text FROM budget_line l JOIN budget_revision r ON r.id = l.revision_id WHERE r.estimate_id = ? AND l.option_id = 208001", String.class, Long.parseLong(foreign));
        rejected(RESOURCE_FORBIDDEN, () -> revise("foreign-line", update(1, Map.of("lineId", foreignLine, "quantity", "1"))));
        assertThat(currentVersion()).isEqualTo(1);
    }

    @Test
    void savingProjectLineAsTemplateCreatesOnlyPrivateDisabledItemWithoutLeakingPriceOrMutatingBudget() throws Exception {
        revise("project-line", add(1, drainage("drainage", "20", 18_000L)));
        String lineId = customLineId();
        var oldRevision = revisionRows(2); var oldLines = lineRows(2);
        var request = templateRequest(2);
        JsonNode template = ok(postTemplate("template", lineId, request));
        assertThat(template.path("enabled").asBoolean()).isFalse();
        assertThat(template.path("publicSelectable").asBoolean()).isFalse();
        assertThat(template.path("itemId").isTextual()).isTrue();
        long id = Long.parseLong(template.path("itemId").asText());
        assertThat(jdbc.queryForMap("SELECT source, enabled, public_selectable, tenant_id FROM budget_item WHERE id = ?", id))
                .containsEntry("source", "CUSTOM_TEMPLATE").containsEntry("enabled", false).containsEntry("public_selectable", false).containsEntry("tenant_id", 0L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_option WHERE item_id = ?", Integer.class, id)).isZero();
        assertThat(count("budget_item_price")).isEqualTo(13);
        assertThat(template.toString()).doesNotContain("内部专用", "18000", "unitPriceCents", "internalNote", budgetId);
        assertThat(revisionRows(2)).isEqualTo(oldRevision);
        assertThat(lineRows(2)).isEqualTo(oldLines);
        assertThat(currentVersion()).isEqualTo(2);
        assertThat(ok(postTemplate("template", lineId, request))).isEqualTo(template);
        var changed = new LinkedHashMap<>(request); changed.put("name", "另一个模板名");
        rejected(IDEMPOTENCY_KEY_REUSED, () -> revisions.saveAsTemplate(99, budgetId, lineId, "template", changed));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'", Integer.class)).isEqualTo(1);
        assertThat(count("budget_catalog_command")).isEqualTo(2);
        assertThat(count("audit_event")).isEqualTo(3);
    }

    @Test
    void templateSaveRejectsStandardOrHistoricalLinesAndAuditFailureRollsBackCatalogAndReceipt() {
        rejected(BUDGET_INPUT_INVALID, () -> revisions.saveAsTemplate(99, budgetId, optionLineId(208001), "standard-template", templateRequest(1)));
        revise("project-line", add(1, drainage("drainage", "20", 18_000L)));
        String oldLine = customLineId();
        revise("new-revision", update(2, Map.of("lineId", oldLine, "quantity", "21")));
        rejected(RESOURCE_FORBIDDEN, () -> revisions.saveAsTemplate(99, budgetId, oldLine, "old-template", templateRequest(3)));
        var state = databaseState();
        var failing = service(message -> { new JdbcAuditPort(ds).record(message); throw new IllegalStateException("template audit failed"); });
        String line = customLineId();
        assertThatThrownBy(() -> failing.saveAsTemplate(99, budgetId, line, "template-retry", templateRequest(3))).isInstanceOf(IllegalStateException.class);
        assertThat(databaseState()).isEqualTo(state);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'", Integer.class)).isZero();
        revisions.saveAsTemplate(99, budgetId, line, "template-retry", templateRequest(3));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'", Integer.class)).isEqualTo(1);
    }

    @Test
    void realMethodSecuritySeparatesReadEditAndTemplateConfigurationAndUsesSessionActor() throws Exception {
        permissions.allowed = Set.of();
        assertThat(response(get(ADMIN)).path("code").asInt()).isEqualTo(403);
        permissions.allowed = Set.of("design:budget:query");
        assertThat(ok(get(ADMIN + "/{id}", budgetId)).path("budgetId").asText()).isEqualTo(budgetId);
        assertThat(response(postRevision(budgetId, "readonly", add(1, drainage("drainage", "20", 18_000L)))).path("code").asInt()).isEqualTo(403);
        permissions.allowed = Set.of("design:budget:configure");
        assertThat(response(postRevision(budgetId, "configure-only", add(1, drainage("drainage", "20", 18_000L)))).path("code").asInt()).isEqualTo(403);
        permissions.allowed = Set.of("design:budget:edit");
        login(123, 1, null);
        ok(postRevision(budgetId, "editor", add(1, drainage("drainage", "20", 18_000L))));
        assertThat(jdbc.queryForObject("SELECT actor_id FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isEqualTo(123L);
        assertThat(response(postTemplate("no-configure", customLineId(), templateRequest(2))).path("code").asInt()).isEqualTo(403);
        permissions.allowed = Set.of("design:budget:configure");
        assertThat(response(postTemplate("no-edit", customLineId(), templateRequest(2))).path("code").asInt()).isEqualTo(403);
        permissions.allowed = Set.of("design:budget:edit", "design:budget:configure");
        ok(postTemplate("both-permissions", customLineId(), templateRequest(2)));
        assertThat(jdbc.queryForList("SELECT actor_id FROM audit_event WHERE actor_type = 'ADMIN' ORDER BY id", String.class)).containsOnly("123");
    }

    @Test
    void quoteHttpPermissionsSeparateDraftingFromPublicationAndUseSessionActor() throws Exception {
        var draftBody = Map.<String, Object>of("revisionId", initialRevisionId, "expectedVersion", 1,
                "finalPriceCents", TOTAL, "reason", "管理员确认正式报价");
        permissions.allowed = Set.of("design:budget:query");
        assertThat(ok(get(ADMIN + "/{id}/quotes", budgetId)).size()).isZero();
        assertThat(response(post(ADMIN + "/{id}/quotes", budgetId).header("Idempotency-Key", "http-draft-denied")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(draftBody))).path("code").asInt()).isEqualTo(403);

        permissions.allowed = Set.of("design:budget:quote");
        login(123, 1, null);
        JsonNode draft = ok(post(ADMIN + "/{id}/quotes", budgetId).header("Idempotency-Key", "http-draft")
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(draftBody)));
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM budget_quote WHERE id = ?", Long.class,
                Long.parseLong(draft.path("quoteId").asText()))).isEqualTo(123L);
        assertThat(response(post(APP + "/budget/quotes/{quoteId}/publish", draft.path("quoteId").asText())
                .header("Idempotency-Key", "http-publish-denied").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(Map.of("expectedVersion", 1, "reason", "发布报价")))).path("code").asInt()).isEqualTo(403);

        permissions.allowed = Set.of("design:budget:quote-publish");
        JsonNode published = ok(post(APP + "/budget/quotes/{quoteId}/publish", draft.path("quoteId").asText())
                .header("Idempotency-Key", "http-publish").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsBytes(Map.of("expectedVersion", 1, "reason", "发布报价"))));
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.path("publishedBy").asText()).isEqualTo("123");
    }

    @Test
    void wrongLoginTenantVisitTenantAndMissingSessionCannotReadOrMutateBusinessTenantZero() throws Exception {
        var request = add(1, drainage("drainage", "20", 18_000L));
        for (long tenant : List.of(0L, 2L)) {
            login(99, tenant, null);
            assertThat(response(get(ADMIN + "/{id}", budgetId)).path("code").asInt()).isEqualTo(403);
            assertThat(response(postRevision(budgetId, "wrong-tenant", request)).path("code").asInt()).isEqualTo(403);
        }
        login(99, 1, 2L);
        assertThat(response(get(ADMIN)).path("code").asInt()).isEqualTo(403);
        SecurityContextHolder.clearContext();
        assertThat(response(get(ADMIN + "/{id}/revisions", budgetId)).path("code").asInt()).isEqualTo(403);
        login(99, 1, null);
        TenantContextHolder.setTenantId(2L);
        JsonNode valid = ok(postRevision(budgetId, "server-scope", request).header("tenant-id", "2"));
        assertThat(valid.path("totalCents").asLong()).isEqualTo(SUPPLEMENTED);
        assertThat(jdbc.queryForObject("SELECT tenant_id FROM budget_revision WHERE id = ?", Long.class, currentRevision())).isZero();
        assertThat(count("budget_catalog_command")).isEqualTo(1);
    }

    @Test
    void adminReadContractsExposeInternalStringIdsAndFrozenHistoryWithCorrectPagination() throws Exception {
        JsonNode before = ok(get(ADMIN + "/{id}", budgetId));
        assertThat(before.path("readOnly").asBoolean()).isFalse();
        assertThat(before.path("revisionNo").asInt()).isEqualTo(1);
        assertThat(before.path("lines").size()).isEqualTo(13);
        for (String field : List.of("budgetId", "projectId", "userId", "resultVersionId", "revisionId")) assertThat(before.path(field).isTextual()).as(field).isTrue();
        for (JsonNode line : before.path("lines")) {
            assertThat(line.path("lineId").isTextual()).isTrue();
            assertThat(Long.parseLong(line.path("lineId").asText())).isGreaterThan(9_007_199_254_740_991L);
            assertThat(line.path("quantity").isTextual()).isTrue();
            assertThat(line.path("unitPriceCents").isIntegralNumber()).isTrue();
        }
        assertThat(before.path("inputSnapshot").toString()).doesNotContain("publicResult");
        revise("draft-missing", add(1, drainage("drainage", "20", null)));
        JsonNode old = ok(get(ADMIN + "/{id}", budgetId).param("revisionId", initialRevisionId));
        assertThat(old.path("currentVersion").asInt()).isEqualTo(2);
        assertThat(old.path("revisionNo").asInt()).isEqualTo(1);
        assertThat(old.path("readOnly").asBoolean()).isTrue();
        assertThat(old.path("lines")).isEqualTo(before.path("lines"));
        JsonNode history = ok(get(ADMIN + "/{id}/revisions", budgetId));
        assertThat(history.size()).isEqualTo(2);
        assertThat(history.get(0).path("revisionNo").asInt()).isEqualTo(2);
        assertThat(history.get(0).path("actorId").asText()).isEqualTo("99");
        assertThat(history.get(1).path("actorType").asText()).isEqualTo("USER");
        JsonNode page = ok(get(ADMIN).param("projectId", Long.toString(PROJECT)).param("completeness", "INCOMPLETE").param("pageNo", "1").param("pageSize", "1"));
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("list").size()).isEqualTo(1);
        assertThat(page.path("list").get(0).path("budgetId").asText()).isEqualTo(budgetId);
        assertThat(ok(get(ADMIN).param("completeness", "COMPLETE")).path("total").asLong()).isZero();
        assertThat(ok(get(ADMIN).param("pageNo", "2").param("pageSize", "1")).path("list").size()).isZero();
        for (Map<String, String> invalid : List.of(Map.of("pageNo", "0"), Map.of("pageSize", "0"),
                Map.of("pageSize", "101"), Map.of("completeness", "UNKNOWN"), Map.of("projectId", "9223372036854775808"))) {
            var request = get(ADMIN);
            invalid.forEach(request::param);
            assertThat(response(request).path("code").asInt()).isEqualTo(BUDGET_INPUT_INVALID.getCode());
        }
    }

    @Test
    void legacyBudgetsStayOnOldAppPathAndAreNotEditableOrInventedAsAdminItemizedRows() throws Exception {
        legacy.createRuleVersion("TEST_A", "FRAME", "NORMAL", 120_000, 150_000, java.time.Instant.parse("2020-01-01T00:00:00Z"));
        var old = legacy.createEstimate(1, PROJECT, "TEST_A", "FRAME", "NORMAL", 240, VERSION);
        String id = Long.toString(old.estimateId());
        rejected(BUDGET_INPUT_INVALID, () -> revisions.get(id, null));
        rejected(BUDGET_INPUT_INVALID, () -> revisions.history(id));
        rejected(BUDGET_INPUT_INVALID, () -> revisions.revise(99, id, "legacy-edit", add(1, drainage("drainage", "20", 18_000L))));
        rejected(BUDGET_INPUT_INVALID, () -> revisions.saveAsTemplate(99, id, "1", "legacy-template", templateRequest(1)));
        JsonNode adminList = ok(get(ADMIN));
        assertThat(adminList.path("total").asLong()).isEqualTo(1);
        assertThat(adminList.path("list").get(0).path("budgetId").asText()).isEqualTo(budgetId);
        JsonNode oldApp = ok(get(APP + "/budget-estimates/{id}", id).header("Authorization", "Bearer owner"));
        assertThat(oldApp.path("totalMinCents").asLong()).isEqualTo(28_800_000L);
        assertThat(oldApp.path("totalMaxCents").asLong()).isEqualTo(36_000_000L);
        assertThat(oldApp.has("lines")).isFalse();
        assertThat(count("budget_revision")).isEqualTo(1);
        assertThat(count("budget_catalog_command")).isZero();
    }

    @Test
    void deletedProjectsVersionsAndCrossBudgetRevisionIdsAreInaccessibleIncludingCommandReplay() {
        JsonNode another = tree(appBudgets.create(1, PROJECT, initialRequest(OPTIONS, Long.toString(VERSION)), "another-budget"));
        rejected(RESOURCE_FORBIDDEN, () -> revisions.get(budgetId, another.path("revisionId").asText()));
        var request = add(1, drainage("drainage", "20", 18_000L));
        revise("replay-after-delete", request);
        jdbc.update("UPDATE design_result_version SET deleted = TRUE WHERE id = ?", VERSION);
        rejected(RESOURCE_FORBIDDEN, () -> revisions.get(budgetId, null));
        rejected(RESOURCE_FORBIDDEN, () -> revise("replay-after-delete", request));
        assertThat(revisions.list(null, null, 1, 100).getTotal()).isZero();
        jdbc.update("UPDATE design_result_version SET deleted = FALSE WHERE id = ?", VERSION);
        jdbc.update("UPDATE design_project SET deleted = TRUE WHERE id = ?", PROJECT);
        rejected(RESOURCE_FORBIDDEN, () -> revisions.history(budgetId));
        rejected(RESOURCE_FORBIDDEN, () -> revisions.saveAsTemplate(99, budgetId, customLineId(), "deleted-template", templateRequest(2)));
        assertThat(revisions.list(Long.toString(PROJECT), null, 1, 100).getTotal()).isZero();
        assertThat(count("budget_catalog_command")).isEqualTo(1);
    }

    @Test
    void projectDecimalsRoundPerLineBeforeSummingAndCannotReuseDisplayedTotalAsAnInput() {
        var first = drainage("half-a", "0.5000", 1L);
        var second = drainage("half-b", "0.5000", 1L);
        revise("half-cent-lines", patch(1, List.of(), List.of(first, second), List.of()));
        assertRevision(2, "COMPLETE", 48_202_002L, 48_202_002L);
        assertThat(jdbc.queryForList("SELECT amount_cents FROM budget_line WHERE revision_id = ? AND source = 'PROJECT_CUSTOM' ORDER BY id", Long.class, currentRevision())).containsExactly(1L, 1L);
        var precise = drainage("precise", "12.3450", 1001L); precise.put("itemCode", "PRECISE");
        revise("precise-line", add(2, precise));
        assertRevision(3, "COMPLETE", 48_214_359L, 48_214_359L);
        assertThat(currentCustom("PRECISE").get("amount_cents")).isEqualTo(12_357L);
    }

    private AdminBudgetRevisionService service(AuditPort audit) {
        return new AdminBudgetRevisionService(ds, transactions, audit);
    }

    private Map<String, Object> inputs() {
        return new LinkedHashMap<>(Map.of("regionCode", "TEST_A", "footprintArea", "120", "floorCount", 2, "roofArea", "112",
                "quantities", new LinkedHashMap<>(Map.of("DOOR_HOUSEHOLDS", "1", "CULTURE_STONE_LENGTH", "44",
                "LIGHTING_WASHER_LENGTH", "20", "LIGHTING_STRIP_LENGTH", "30", "WATERPROOF_AREA", "20"))));
    }

    private void project(long id, long user, long requirement) {
        jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (?, ?, 'SELF_UPLOAD')", id, user);
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                + "VALUES (?, ?, 1, CAST(? AS jsonb), '2026-01-01T00:00:00Z')", requirement, id, tree(Map.of("budgetInputs", inputs())).toString());
    }

    private void replaceInputs(Map<String, Object> input) {
        jdbc.update("UPDATE design_requirement_snapshot SET inputs = CAST(? AS jsonb) WHERE id = ?", tree(Map.of("budgetInputs", input)).toString(), REQUIREMENT);
    }

    private void selectBudget(JsonNode response) {
        budgetId = response.path("budgetId").asText();
        initialRevisionId = response.path("revisionId").asText();
        initialPublic = response;
    }

    private void catalogTemplate(long itemId, long optionId, long tenant) {
        jdbc.update("INSERT INTO budget_item (id, code, name, category, source, enabled, public_selectable, tenant_id) "
                + "VALUES (?, ?, '后台排水模板', 'EXTERIOR', 'CUSTOM_TEMPLATE', TRUE, FALSE, ?)", itemId, "DRAIN_" + itemId, tenant);
        jdbc.update("INSERT INTO budget_option (id, item_id, code, label, selection_group, unit, quantity_source, enabled, tenant_id) "
                + "VALUES (?, ?, 'FIXED', '模板固定费', 'CUSTOM', 'ITEM', 'FIXED_ONE', TRUE, ?)", optionId, itemId, tenant);
    }

    private JsonNode findLine(JsonNode detail, String code) {
        for (JsonNode line : detail.path("lines")) if (code.equals(line.path("itemCode").asText())) return line;
        throw new AssertionError("Missing line " + code + ": " + detail);
    }

    private List<Map<String, Object>> frozenLineValues(int revisionNo) {
        return jdbc.queryForList("SELECT line_key, item_id, option_id, price_version_id, item_code, public_name, option_label, category, source, unit, quantity, "
                + "unit_price_cents, amount_cents, status, free_reason, excluded_reason, internal_note FROM budget_line l JOIN budget_revision r ON r.id = l.revision_id "
                + "WHERE r.estimate_id = ? AND r.revision_no = ? AND l.source = 'STANDARD' ORDER BY l.sort_order, l.id", Long.parseLong(budgetId), revisionNo);
    }

    private Map<String, Object> databaseState() {
        return Map.of("masters", jdbc.queryForList("SELECT * FROM budget_estimate ORDER BY id"),
                "revisions", jdbc.queryForList("SELECT * FROM budget_revision ORDER BY id"),
                "lines", jdbc.queryForList("SELECT * FROM budget_line ORDER BY id"),
                "commands", jdbc.queryForList("SELECT * FROM budget_catalog_command ORDER BY actor_id, operation, idempotency_key"),
                "audit", jdbc.queryForList("SELECT * FROM audit_event ORDER BY id"),
                "customItems", jdbc.queryForList("SELECT * FROM budget_item WHERE source = 'CUSTOM_TEMPLATE' ORDER BY id"));
    }

    private Map<String, Object> templateRequest(int expectedVersion) {
        return Map.of("expectedVersion", expectedVersion, "code", "DRAIN_TEMPLATE", "name", "排水沟通用模板", "reason", "仅保存通用身份不含项目专用价格");
    }

    private MockHttpServletRequestBuilder postTemplate(String key, String lineId, Map<String, Object> body) throws Exception {
        return post(ADMIN + "/{budgetId}/items/{lineId}/template", budgetId, lineId).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
    }

    private int revisionCode(long actor, String key, Map<String, Object> body) {
        try { revisions.revise(actor, budgetId, key, body); return 0; }
        catch (ServiceException exception) { return exception.getCode(); }
    }

    private List<Object> race(Callable<?> one, Callable<?> two) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out"); return one.call(); });
            Future<?> second = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out"); return two.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    private Map<String, Object> initialRequest(List<String> options, String version) {
        var body = new LinkedHashMap<String, Object>();
        body.put("resultVersionId", version);
        body.put("inputOverrides", Map.of());
        body.put("optionIds", options);
        return body;
    }

    private void price(long id, long regionId, long optionId, long cents) {
        jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id, unit_price_cents, status, effective_at, published_by, published_at) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', '2020-01-01T00:00:00Z', 99, now())", id, regionId, optionId, cents);
    }

    private Map<String, Object> patch(int expected, List<Map<String, Object>> updates, List<Map<String, Object>> additions, List<String> remove) {
        return new LinkedHashMap<>(Map.of("expectedVersion", expected, "reason", "现场核定本项目预算补项",
                "updates", updates, "additions", additions, "removeLineIds", remove));
    }

    private Map<String, Object> add(int version, Map<String, Object> addition) { return patch(version, List.of(), List.of(addition), List.of()); }
    private Map<String, Object> update(int version, Map<String, Object> update) { return patch(version, List.of(update), List.of(), List.of()); }

    private Map<String, Object> drainage(String key, String quantity, Long cents) {
        var line = new LinkedHashMap<String, Object>();
        line.put("kind", "PROJECT_CUSTOM");
        line.put("clientKey", key);
        line.put("itemCode", "DRAINAGE");
        line.put("publicName", "排水沟施工");
        line.put("category", "EXTERIOR");
        line.put("unit", "METER");
        line.put("quantity", quantity);
        line.put("unitPriceCents", cents);
        line.put("internalNote", "内部专用施工说明");
        return line;
    }

    private JsonNode revise(String key, Map<String, Object> request) { return tree(revisions.revise(99, budgetId, key, request)); }
    private long currentRevision() { return jdbc.queryForObject("SELECT r.id FROM budget_revision r JOIN budget_estimate b ON b.id = r.estimate_id "
            + "AND b.current_revision = r.revision_no WHERE b.id = ?", Long.class, Long.parseLong(budgetId)); }
    private int currentVersion() { return jdbc.queryForObject("SELECT current_revision FROM budget_estimate WHERE id = ?", Integer.class, Long.parseLong(budgetId)); }
    private List<Map<String, Object>> currentLines() { return jdbc.queryForList("SELECT * FROM budget_line WHERE revision_id = ? ORDER BY id", currentRevision()); }
    private Map<String, Object> currentCustom(String code) { return jdbc.queryForMap("SELECT * FROM budget_line WHERE revision_id = ? AND source = 'PROJECT_CUSTOM' AND item_code = ?", currentRevision(), code); }
    private String customLineId() { return String.valueOf(currentCustom("DRAINAGE").get("id")); }
    private String optionLineId(long option) { return jdbc.queryForObject("SELECT id::text FROM budget_line WHERE revision_id = ? AND option_id = ?", String.class, currentRevision(), option); }
    private List<Map<String, Object>> revisionRows(int number) { return jdbc.queryForList("SELECT * FROM budget_revision WHERE estimate_id = ? AND revision_no = ?", Long.parseLong(budgetId), number); }
    private List<Map<String, Object>> lineRows(int number) { return jdbc.queryForList("SELECT l.* FROM budget_line l JOIN budget_revision r ON r.id = l.revision_id "
            + "WHERE r.estimate_id = ? AND r.revision_no = ? ORDER BY l.id", Long.parseLong(budgetId), number); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }

    private void assertRevision(int number, String completeness, long subtotal, Long total) {
        assertThat(currentVersion()).isEqualTo(number);
        Map<String, Object> row = revisionRows(number).get(0);
        assertThat(row).containsEntry("completeness", completeness).containsEntry("priced_subtotal_cents", subtotal).containsEntry("total_cents", total);
    }

    private void rejected(ErrorCode expected, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class, error -> assertThat(error.getCode()).isEqualTo(expected.getCode()));
    }

    private void login(long actor, long tenant, Long visitTenant) {
        SecurityFrameworkUtils.setLoginUser(new LoginUser().setId(actor).setUserType(2).setTenantId(tenant).setVisitTenantId(visitTenant), new MockHttpServletRequest());
    }

    private JsonNode tree(Object value) {
        try { return JSON.readTree(JSON.writeValueAsBytes(value)); }
        catch (java.io.IOException exception) { throw new AssertionError("Invalid test JSON fixture", exception); }
    }

    private MockHttpServletRequestBuilder postRevision(String id, String key, Map<String, Object> body) throws Exception {
        return post(ADMIN + "/{id}/revisions", id).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
    }

    private JsonNode response(MockHttpServletRequestBuilder request) throws Exception {
        return JSON.readTree(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode ok(MockHttpServletRequestBuilder request) throws Exception {
        JsonNode result = response(request);
        assertThat(result.path("code").asInt()).as(result.toString()).isZero();
        return result.path("data");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableMethodSecurity
    static class SecurityConfiguration {}

    public static class PermissionProbe {
        Set<String> allowed = Set.of();
        public boolean hasPermission(String permission) { return allowed.contains(permission); }
    }
}
