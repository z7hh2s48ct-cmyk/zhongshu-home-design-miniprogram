package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.biz.infra.logger.ApiErrorLogCommonApi;
import cn.iocoder.yudao.framework.common.exception.ErrorCode;
import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.framework.web.core.handler.GlobalExceptionHandler;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.budget.ItemizedBudgetService;
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
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * T10-06: actual PostgreSQL transactions and actual MVC routing/JSON/advice.
 * The identity port is a controlled session fixture, not a claim to exercise OAuth or a live HTTP server.
 * All monetary expectations below are independently transcribed workbook arithmetic, never calculator output.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BudgetItemizedT10ContractTest {
    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final long PROJECT = 9007199254741001L;
    private static final long VERSION = 9007199254742001L;
    private static final long REQUIREMENT = 9007199254743001L;
    private static final long REGION = 810001L;
    private static final long TOTAL = 48_202_000L;
    private static final String BASE = "/design/v1";
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
    private BudgetService legacy;
    private MockMvc mvc;
    private List<Map<String, Object>> originalItems;
    private List<Map<String, Object>> originalOptions;
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
        originalItems = jdbc.queryForList("SELECT id, name FROM budget_item");
        originalOptions = jdbc.queryForList("SELECT id, label FROM budget_option");
        projects = new DesignProjectService(ds, transactions, mock(AiJobPort.class), null, null, null);
        legacy = new BudgetService(ds, projects);
    }

    @BeforeEach
    void seedIsolatedContainer() {
        // These are exclusively this class's disposable Testcontainer tables, never a configured application DB.
        jdbc.execute("TRUNCATE budget_quote, budget_line, budget_revision, budget_estimate, budget_item_price, "
                + "budget_region, budget_app_command, budget_rule_version, design_project, design_requirement_snapshot, "
                + "design_result_version, audit_event");
        jdbc.update("DELETE FROM budget_option WHERE item_id IN (SELECT id FROM budget_item WHERE source = 'CUSTOM_TEMPLATE')");
        jdbc.update("DELETE FROM budget_item WHERE source = 'CUSTOM_TEMPLATE'");
        jdbc.update("UPDATE budget_item SET enabled = TRUE, public_selectable = TRUE, deleted = FALSE");
        jdbc.update("UPDATE budget_option SET enabled = TRUE, deleted = FALSE");
        originalItems.forEach(row -> jdbc.update("UPDATE budget_item SET name = ? WHERE id = ?", row.get("name"), row.get("id")));
        originalOptions.forEach(row -> jdbc.update("UPDATE budget_option SET label = ? WHERE id = ?", row.get("label"), row.get("id")));
        jdbc.update("INSERT INTO budget_region (id, code, name, enabled) VALUES (?, 'TEST_A', '金样测试地区', TRUE), "
                + "(810002, 'TEST_B', '另一地区', TRUE)", REGION);
        for (int i = 0; i < OPTIONS.size(); i++) price(820001L + i, REGION, Long.parseLong(OPTIONS.get(i)), PRICES[i]);
        project(PROJECT, 1, REQUIREMENT, inputs());
        version(VERSION, PROJECT, "{}");
        budgets = service(new JdbcAuditPort(ds));
        var controller = new AppBudgetController();
        ReflectionTestUtils.setField(controller, "budgetService", legacy);
        ReflectionTestUtils.setField(controller, "itemizedBudgetService", budgets);
        ReflectionTestUtils.setField(controller, "designProjectService", projects);
        ReflectionTestUtils.setField(controller, "identitySessionPort", identities);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON))
                .setControllerAdvice(new GlobalExceptionHandler("t10-contract", mock(ApiErrorLogCommonApi.class))).build();
    }

    @Test
    void goldenCreatesTenParentsThirteenLinesAndIndependentFrozenAmounts() throws Exception {
        JsonNode data = ok(createRequest(body(), "golden", "owner"));
        assertThat(data.path("model").asText()).isEqualTo("ITEMIZED_V1");
        assertThat(data.path("projectId").asText()).isEqualTo(Long.toString(PROJECT));
        assertThat(data.path("resultVersionId").asText()).isEqualTo(Long.toString(VERSION));
        assertThat(data.path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(data.path("totalCents").asLong()).isEqualTo(TOTAL);
        assertThat(data.path("pricedSubtotalCents").asLong()).isEqualTo(TOTAL);
        assertThat(data.path("categoryTotals").path("BODY").asLong()).isEqualTo(35_584_000L);
        assertThat(data.path("categoryTotals").path("EXTERIOR").asLong()).isEqualTo(12_618_000L);
        assertThat(data.path("items").size()).isEqualTo(10);
        assertThat(item(data, "DOORS_WINDOWS").path("lines").size()).isEqualTo(2);
        assertThat(item(data, "DOORS_WINDOWS").path("amountCents").asLong()).isEqualTo(4_740_000L);
        assertThat(item(data, "LIGHTING").path("amountCents").asLong()).isEqualTo(420_000L);
        assertThat(item(data, "WATERPROOF_LIGHTNING").path("amountCents").asLong()).isEqualTo(670_000L);
        assertThat(count("budget_estimate")).isEqualTo(1);
        assertThat(count("budget_revision")).isEqualTo(1);
        assertThat(count("budget_line")).isEqualTo(13);
        assertThat(count("budget_app_command")).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT sum(amount_cents) FROM budget_line", Long.class)).isEqualTo(TOTAL);
        assertThat(jdbc.queryForObject("SELECT quantity::text FROM budget_line WHERE option_id = 208020", String.class)).isEqualTo("60");
        assertThat(jdbc.queryForObject("SELECT unit_price_cents FROM budget_line WHERE option_id = 208001", Long.class)).isEqualTo(52_000L);
        assertThat(jdbc.queryForObject("SELECT price_version_id FROM budget_line WHERE option_id = 208001", Long.class)).isEqualTo(820001L);
        assertThat(jdbc.queryForObject("SELECT input_snapshot::text FROM budget_revision", String.class))
                .contains(Long.toString(REQUIREMENT), "DOOR_HOUSEHOLDS");
    }

    @Test
    void publicJsonUsesStringIdsAndExplicitProjectionWithoutInternalPricingOrQuantities() throws Exception {
        JsonNode data = ok(createRequest(body(), "projection", "owner"));
        assertThat(fields(data)).containsExactlyInAnyOrder("budgetId", "revisionId", "model", "projectId", "projectName",
                "resultVersionId", "schemeName", "regionName", "inputSummary", "completeness", "pricedSubtotalCents",
                "totalCents", "categoryTotals", "items", "missingItems", "warnings", "disclaimer", "createdAt", "saved");
        for (String id : List.of("budgetId", "revisionId", "projectId", "resultVersionId")) {
            assertThat(data.path(id).isTextual()).as(id).isTrue();
            assertThat(Long.parseLong(data.path(id).asText())).isGreaterThan(9_007_199_254_740_991L);
        }
        for (JsonNode parent : data.path("items")) {
            assertThat(fields(parent)).containsExactlyInAnyOrder("itemId", "itemCode", "category", "publicName", "source",
                    "completeness", "pricedSubtotalCents", "amountCents", "lines");
            assertThat(parent.path("itemId").isTextual()).isTrue();
            for (JsonNode line : parent.path("lines")) {
                assertThat(fields(line)).containsExactlyInAnyOrder("lineId", "optionLabel", "status", "amountCents");
                assertThat(line.path("lineId").isTextual()).isTrue();
            }
        }
        assertThat(data.toString()).doesNotContain("unitPrice", "unit_price", "priceVersion", "quantity", "quantities",
                "quantitySource", "quantityKey", "sourceReference", "pricingSnapshot", "freeReason", "excludedReason",
                "internalNote", "DOOR_HOUSEHOLDS", "private fixture prompt", "requestHash");
        assertThat(data.path("disclaimer").asText()).contains("参考");
        assertThat(data.path("saved").asBoolean()).isFalse();
    }

    @Test
    void absentStandardSelectionIsIncompleteRatherThanAnImplicitZero() {
        JsonNode result = create(withOptions(without("208001")), "missing-foundation");
        incomplete(result, 41_962_000L);
        assertThat(item(result, "FOUNDATION").path("amountCents").isNull()).isTrue();
        assertThat(item(result, "FOUNDATION").path("lines").get(0).path("status").asText()).isEqualTo("MISSING_BOTH");
        assertThat(result.path("missingItems").toString()).contains("地基基础");
        JsonNode empty = create(withOptions(List.of()), "empty");
        incomplete(empty, 0);
        assertThat(empty.path("items").size()).isEqualTo(10);
        assertThat(empty.path("missingItems").size()).isEqualTo(10);
    }

    @Test
    void partiallySelectedDoorWindowAndWaterproofLightningGroupsKeepOnlyPricedChildren() {
        JsonNode noWindow = create(withOptions(without("208020")), "missing-window");
        incomplete(noWindow, 45_262_000L);
        assertThat(item(noWindow, "DOORS_WINDOWS").path("pricedSubtotalCents").asLong()).isEqualTo(1_800_000L);
        assertThat(item(noWindow, "DOORS_WINDOWS").path("amountCents").isNull()).isTrue();
        assertThat(item(noWindow, "DOORS_WINDOWS").path("lines").size()).isEqualTo(2);
        JsonNode noLightning = create(withOptions(without("208034")), "missing-lightning");
        incomplete(noLightning, 47_702_000L);
        assertThat(item(noLightning, "WATERPROOF_LIGHTNING").path("pricedSubtotalCents").asLong()).isEqualTo(170_000L);
    }

    @Test
    void lightingNeedsOneGroupButDoesNotInventUnselectedLampGroups() {
        JsonNode one = create(withOptions(without("208030")), "one-light");
        assertThat(one.path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(one.path("totalCents").asLong()).isEqualTo(48_022_000L);
        assertThat(item(one, "LIGHTING").path("lines").size()).isEqualTo(1);
        incomplete(create(withOptions(without("208029", "208030")), "no-light"), 47_782_000L);
    }

    @Test
    void missingRegionalPriceDoesNotUseDraftExpiredFutureOrAnotherRegionsPrice() {
        jdbc.update("UPDATE budget_item_price SET status = 'DRAFT' WHERE option_id = 208009");
        price(830101, 810002, 208009, 1);
        price(830102, REGION, 208009, 2);
        jdbc.update("UPDATE budget_item_price SET effective_at = '2100-01-01' WHERE id = 830102");
        price(830103, REGION, 208009, 3);
        jdbc.update("UPDATE budget_item_price SET expires_at = '2021-01-01' WHERE id = 830103");
        JsonNode result = create(body(), "missing-price");
        incomplete(result, 42_378_000L);
        assertThat(item(result, "ROOF").path("lines").get(0).path("status").asText()).isEqualTo("MISSING_PRICE");
        assertThat(jdbc.queryForObject("SELECT price_version_id FROM budget_line WHERE option_id = 208009", Long.class)).isNull();
    }

    @Test
    void missingProfessionalQuantityIsNotGuessedAndMissingRoofDoesNotReuseFootprint() {
        Map<String, Object> value = inputs();
        @SuppressWarnings("unchecked") var quantities = new LinkedHashMap<>((Map<String, Object>) value.get("quantities"));
        quantities.remove("CULTURE_STONE_LENGTH");
        value.put("quantities", quantities);
        replaceInputs(value);
        JsonNode result = create(body(), "missing-quantity");
        incomplete(result, 47_674_000L);
        assertThat(item(result, "CULTURE_STONE").path("lines").get(0).path("status").asText()).isEqualTo("MISSING_QUANTITY");
        value.remove("roofArea");
        replaceInputs(value);
        incomplete(create(body(), "missing-roof-and-stone"), 41_850_000L);
    }

    @Test
    void actualAreaConflictKeepsActualAreaButNeverClaimsComplete() {
        Map<String, Object> value = inputs();
        value.put("buildingArea", "251.2");
        replaceInputs(value);
        JsonNode result = create(body(), "actual-conflict");
        // Difference: 11.2 * (98000 + 12000 + 12000) + 2.8 * 49000 = 1,503,600 cents.
        incomplete(result, 49_705_600L);
        assertThat(result.path("warnings").toString()).contains("BUILDING_AREA_REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT quantity::text FROM budget_line WHERE option_id = 208006", String.class)).isEqualTo("251.2");
        assertThat(jdbc.queryForObject("SELECT quantity::text FROM budget_line WHERE option_id = 208020", String.class)).isEqualTo("62.8");
    }

    @Test
    void explicitWindowQuantityOverridesTheSingleAreaCoefficient() {
        var value = inputs();
        @SuppressWarnings("unchecked") var quantities = new LinkedHashMap<>((Map<String, Object>) value.get("quantities"));
        quantities.put("WINDOW_AREA", "72.1234");
        value.put("quantities", quantities);
        replaceInputs(value);
        JsonNode result = create(body(), "explicit-window");
        assertThat(result.path("totalCents").asLong()).isEqualTo(48_796_047L);
        assertThat(jdbc.queryForObject("SELECT quantity::text FROM budget_line WHERE option_id = 208020", String.class)).isEqualTo("72.1234");
    }

    @Test
    void duplicateIdsAndMutuallyExclusiveFoundationOrDoorSelectionsAreRejected() {
        for (String extra : List.of("208001", "208002", "208016")) {
            var options = new ArrayList<>(OPTIONS);
            options.add(extra);
            rejected(BUDGET_INPUT_INVALID, () -> budgets.create(1, PROJECT, withOptions(options), "exclusive-" + extra));
        }
        assertNoWrites();
    }

    @Test
    void unknownPrivateDisabledDeletedAndForeignTenantSelectionsAreNotAuthorized() {
        rejected(BUDGET_INPUT_INVALID, () -> budgets.create(1, PROJECT, withOptions(List.of("999999")), "unknown"));
        for (String mutation : List.of("enabled = FALSE", "deleted = TRUE")) {
            jdbc.update("UPDATE budget_option SET " + mutation + " WHERE id = 208001");
            assertThatThrownBy(() -> budgets.create(1, PROJECT, body(), "option-" + mutation)).isInstanceOf(ServiceException.class);
            jdbc.update("UPDATE budget_option SET enabled = TRUE, deleted = FALSE WHERE id = 208001");
        }
        jdbc.update("UPDATE budget_item SET public_selectable = FALSE WHERE id = 207001");
        assertThatThrownBy(() -> budgets.create(1, PROJECT, body(), "private")).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE budget_item SET public_selectable = TRUE, enabled = FALSE WHERE id = 207001");
        assertThatThrownBy(() -> budgets.create(1, PROJECT, body(), "parent-disabled")).isInstanceOf(ServiceException.class);
        custom(830001, 830002, true, 1);
        assertThatThrownBy(() -> budgets.create(1, PROJECT, withOptions(List.of("830002")), "foreign-tenant"))
                .isInstanceOf(ServiceException.class);
        assertNoWrites();
    }

    @Test
    void customTemplateRequiresPublicSelectionAndCurrentRegionPrice() {
        custom(830001, 830002, true, 0);
        price(830003, 810002, 830002, 360_000);
        var selected = new ArrayList<>(OPTIONS);
        selected.add("830002");
        assertThatThrownBy(() -> budgets.create(1, PROJECT, withOptions(selected), "custom-other-region"))
                .isInstanceOf(ServiceException.class);
        price(830004, REGION, 830002, 360_000);
        JsonNode result = create(withOptions(selected), "public-custom");
        assertThat(result.path("totalCents").asLong()).isEqualTo(48_562_000L);
        assertThat(result.path("items").size()).isEqualTo(11);
        assertThat(item(result, "CUSTOM_830001").path("source").asText()).isEqualTo("CUSTOM_TEMPLATE");
        jdbc.update("UPDATE budget_item SET public_selectable = FALSE WHERE id = 830001");
        assertThatThrownBy(() -> budgets.create(1, PROJECT, withOptions(selected), "custom-now-private"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void missingDisabledOrUnknownRegionFailsBeforeAnyBudgetWrite() {
        var value = inputs();
        value.remove("regionCode");
        replaceInputs(value);
        rejected(BUDGET_INPUT_INVALID, () -> budgets.create(1, PROJECT, body(), "missing-region"));
        var request = body();
        request.put("inputOverrides", Map.of("regionCode", "UNKNOWN"));
        assertThatThrownBy(() -> budgets.create(1, PROJECT, request, "unknown-region")).isInstanceOf(ServiceException.class);
        jdbc.update("UPDATE budget_region SET enabled = FALSE WHERE id = ?", REGION);
        request.put("inputOverrides", Map.of("regionCode", "TEST_A"));
        assertThatThrownBy(() -> budgets.create(1, PROJECT, request, "disabled-region")).isInstanceOf(ServiceException.class);
        assertNoWrites();
    }

    @Test
    void clientCannotPostPricesTotalsPrivateQuantitiesOrUnrecognizedInputFields() {
        for (String field : List.of("totalCents", "unitPriceCents", "quantity", "lines", "tenantId", "saved")) {
            var request = body();
            request.put(field, 1);
            rejected(BUDGET_INPUT_INVALID, () -> budgets.create(1, PROJECT, request, "spoof-" + field));
        }
        for (Map<String, Object> override : List.<Map<String, Object>>of(Map.of("buildingArea", "1"),
                Map.of("quantities", Map.of("DOOR_HOUSEHOLDS", "100")), Map.of("roofArea", "1.00001"),
                Map.of("floorCount", 1.5), Map.of("footprintArea", "-1"))) {
            var request = body();
            request.put("inputOverrides", override);
            rejected(BUDGET_INPUT_INVALID, () -> budgets.create(1, PROJECT, request, "bad-override"));
        }
        assertNoWrites();
    }

    @Test
    void sameCommandReplaysFrozenResponseAndDifferentBodyConflictsEvenAfterProjectOrPriceChanges() {
        JsonNode original = create(body(), "replay");
        jdbc.update("UPDATE budget_item_price SET unit_price_cents = unit_price_cents + 100 WHERE region_id = ?", REGION);
        var value = inputs();
        value.put("roofArea", "200");
        replaceInputs(value);
        assertThat(create(body(), "replay")).isEqualTo(original);
        rejected(IDEMPOTENCY_KEY_REUSED, () -> budgets.create(1, PROJECT, withOptions(without("208009")), "replay"));
        assertThat(count("budget_estimate")).isEqualTo(1);
        assertThat(count("budget_revision")).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(1);
        assertThat(count("budget_app_command")).isEqualTo(1);
    }

    @Test
    void explicitNullOverrideIsFrozenAndDoesNotShareAnIdempotencyFingerprintWithOmission() {
        create(body(), "empty-overrides");
        var clear = body();
        var overrides = new LinkedHashMap<String, Object>();
        overrides.put("roofArea", null);
        clear.put("inputOverrides", overrides);
        rejected(IDEMPOTENCY_KEY_REUSED, () -> budgets.create(1, PROJECT, clear, "empty-overrides"));
        JsonNode cleared = create(clear, "explicit-clear");
        incomplete(cleared, 42_378_000L);
        assertThat(jdbc.queryForObject("SELECT jsonb_exists(input_snapshot->'inputOverrides', 'roofArea') FROM budget_revision WHERE estimate_id = ?",
                Boolean.class, id(cleared))).isTrue();
        assertThat(jdbc.queryForObject("SELECT jsonb_typeof(input_snapshot->'inputOverrides'->'roofArea') FROM budget_revision WHERE estimate_id = ?",
                String.class, id(cleared))).isEqualTo("null");
        assertThat(create(clear, "explicit-clear")).isEqualTo(cleared);
    }

    @Test
    void eachHalfCentIsRoundedBeforeSummingAndAnExplicitFreePriceRemainsPriced() {
        var value = inputs();
        @SuppressWarnings("unchecked") var quantities = new LinkedHashMap<>((Map<String, Object>) value.get("quantities"));
        quantities.put("CULTURE_STONE_LENGTH", "0.5");
        quantities.put("LIGHTING_WASHER_LENGTH", "0.5");
        value.put("quantities", quantities);
        replaceInputs(value);
        jdbc.update("UPDATE budget_item_price SET unit_price_cents = 1 WHERE option_id IN (208026, 208029)");
        JsonNode rounded = create(body(), "round-lines");
        assertThat(rounded.path("totalCents").asLong()).isEqualTo(47_434_002L);
        assertThat(jdbc.queryForList("SELECT amount_cents FROM budget_line WHERE option_id IN (208026, 208029) ORDER BY option_id", Long.class))
                .containsExactly(1L, 1L);
        jdbc.update("UPDATE budget_item_price SET unit_price_cents = 0, free_reason = '内部免费核定依据' WHERE option_id = 208035");
        JsonNode free = create(body(), "explicit-free");
        assertThat(free.path("completeness").asText()).isEqualTo("COMPLETE");
        assertThat(free.path("totalCents").asLong()).isEqualTo(46_934_002L);
        assertThat(item(free, "INSURANCE").path("lines").get(0).path("status").asText()).isEqualTo("PRICED");
        assertThat(item(free, "INSURANCE").path("amountCents").asLong()).isZero();
        assertThat(free.toString()).doesNotContain("内部免费核定依据", "freeReason");
    }

    @Test
    void concurrentSameKeyCommitsExactlyOneEstimateRevisionReceiptAndAudit() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<JsonNode> command = () -> {
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start gate timed out");
                return create(body(), "concurrent");
            };
            Future<JsonNode> one = pool.submit(command);
            Future<JsonNode> two = pool.submit(command);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(one.get(20, TimeUnit.SECONDS)).isEqualTo(two.get(20, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
        assertThat(count("budget_estimate")).isEqualTo(1);
        assertThat(count("budget_revision")).isEqualTo(1);
        assertThat(count("budget_line")).isEqualTo(13);
        assertThat(count("budget_app_command")).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(1);
    }

    @Test
    void requirementsVersionSequenceMustMatchBeforeFirstCreationButReplayIsNotRecomputed() {
        var request = body();
        request.put("requirementSnapshotIds", List.of(Long.toString(REQUIREMENT + 1)));
        rejected(STATE_VERSION_CONFLICT, () -> budgets.create(1, PROJECT, request, "stale-source"));
        request.put("requirementSnapshotIds", List.of(Long.toString(REQUIREMENT)));
        JsonNode created = create(request, "source-frozen");
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                + "VALUES (?, ?, 2, '{\"budgetInputs\":{\"roofArea\":\"199\"}}', '2026-01-01T12:00:00Z')", REQUIREMENT + 1, PROJECT);
        assertThat(create(request, "source-frozen")).isEqualTo(created);
        rejected(STATE_VERSION_CONFLICT, () -> budgets.create(1, PROJECT, request, "source-new-command"));
    }

    @Test
    void saveMarksOnlySavedAndDoesNotRecalculateAfterPricesNamesOrProjectInputsChange() throws Exception {
        JsonNode created = create(body(), "before-change");
        long id = id(created);
        String revisionBefore = jdbc.queryForObject("SELECT row_to_json(r)::text FROM budget_revision r", String.class);
        String linesBefore = jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(l) ORDER BY id)::text FROM budget_line l", String.class);
        jdbc.update("UPDATE budget_item_price SET unit_price_cents = 1");
        jdbc.update("UPDATE budget_item SET name = '修改后的内部名称' WHERE id = 207001");
        jdbc.update("UPDATE budget_option SET label = '修改后的基础选项' WHERE id = 208001");
        jdbc.update("UPDATE budget_region SET name = '修改后的地区名称' WHERE id = ?", REGION);
        var changed = inputs();
        changed.put("footprintArea", "130");
        replaceInputs(changed);
        JsonNode saved = ok(post(BASE + "/budget-estimates/{id}/save", id)
                .header("Authorization", "Bearer owner").header("Idempotency-Key", "save-first"));
        assertThat(saved.path("budgetId").asText()).isEqualTo(created.path("budgetId").asText());
        assertThat(saved.path("saved").asBoolean()).isTrue();
        JsonNode replay = ok(post(BASE + "/budget-estimates/{id}/save", id)
                .header("Authorization", "Bearer owner").header("Idempotency-Key", "save-first"));
        assertThat(replay).isEqualTo(saved);
        JsonNode detail = ok(get(BASE + "/budget-estimates/{id}", id).header("Authorization", "Bearer owner"));
        assertThat(detail.path("saved").asBoolean()).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) detail).put("saved", false);
        assertThat(detail).isEqualTo(created);
        assertThat(jdbc.queryForObject("SELECT row_to_json(r)::text FROM budget_revision r", String.class)).isEqualTo(revisionBefore);
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(l) ORDER BY id)::text FROM budget_line l", String.class)).isEqualTo(linesBefore);
        assertThat(count("budget_revision")).isEqualTo(1);
        assertThat(count("budget_app_command")).isEqualTo(2);
        assertThat(count("audit_event")).isEqualTo(2);
    }

    @Test
    void saveCanKeepIncompleteDraftAndCannotReuseKeyForAnotherBudget() {
        JsonNode one = create(withOptions(without("208009")), "incomplete");
        budgets.save(1, id(one), "save-draft");
        JsonNode fetched = tree(budgets.get(1, id(one), null).orElseThrow());
        incomplete(fetched, 42_378_000L);
        assertThat(fetched.path("saved").asBoolean()).isTrue();
        JsonNode two = create(body(), "another");
        rejected(IDEMPOTENCY_KEY_REUSED, () -> budgets.save(1, id(two), "save-draft"));
        assertThat(jdbc.queryForObject("SELECT saved FROM budget_estimate WHERE id = ?", Boolean.class, id(two))).isFalse();
    }

    @Test
    void auditFailureRollsBackCreateAndSaveIncludingReceiptsAndAllowsSafeRetry() {
        AuditPort failAfterRealAudit = message -> {
            new JdbcAuditPort(ds).record(message);
            throw new IllegalStateException("audit unavailable after insert");
        };
        ItemizedBudgetService failing = service(failAfterRealAudit);
        assertThatThrownBy(() -> failing.create(1, PROJECT, body(), "retry-create")).isInstanceOf(IllegalStateException.class);
        assertNoWrites();
        JsonNode created = create(body(), "retry-create");
        assertThatThrownBy(() -> failing.save(1, id(created), "retry-save")).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT saved FROM budget_estimate WHERE id = ?", Boolean.class, id(created))).isFalse();
        assertThat(count("budget_app_command")).isEqualTo(1);
        assertThat(count("audit_event")).isEqualTo(1);
        budgets.save(1, id(created), "retry-save");
        assertThat(count("budget_app_command")).isEqualTo(2);
        assertThat(count("audit_event")).isEqualTo(2);
    }

    @Test
    void lineConstraintFailureRollsBackEntireCreationNotJustTheFailingChild() {
        jdbc.execute("ALTER TABLE budget_line ADD CONSTRAINT ck_t10_test_roof_failure CHECK (item_code <> 'ROOF')");
        try {
            assertThatThrownBy(() -> budgets.create(1, PROJECT, body(), "line-failure")).isInstanceOf(RuntimeException.class);
            assertNoWrites();
        } finally {
            jdbc.execute("ALTER TABLE budget_line DROP CONSTRAINT ck_t10_test_roof_failure");
        }
        assertThat(create(body(), "line-failure").path("totalCents").asLong()).isEqualTo(TOTAL);
    }

    @Test
    void foreignProjectVersionAndBudgetAreDeniedAndDeletedSourcesInvalidateEvenReplay() {
        project(PROJECT + 10, 2, REQUIREMENT + 10, inputs());
        project(PROJECT + 20, 1, REQUIREMENT + 20, inputs());
        version(VERSION + 10, PROJECT + 10, "{}");
        version(VERSION + 20, PROJECT + 20, "{}");
        rejected(RESOURCE_FORBIDDEN, () -> budgets.create(2, PROJECT, body(), "foreign-owner"));
        for (long versionId : List.of(VERSION + 10, VERSION + 20, VERSION + 999)) {
            var request = body();
            request.put("resultVersionId", Long.toString(versionId));
            rejected(RESOURCE_FORBIDDEN, () -> budgets.create(1, PROJECT, request, "foreign-version"));
        }
        JsonNode own = create(body(), "owned");
        rejected(RESOURCE_FORBIDDEN, () -> budgets.get(2, id(own), null));
        rejected(RESOURCE_FORBIDDEN, () -> budgets.save(2, id(own), "other-save"));
        rejected(RESOURCE_FORBIDDEN, () -> budgets.listByProject(2, PROJECT));
        jdbc.update("UPDATE design_result_version SET deleted = TRUE WHERE id = ?", VERSION);
        rejected(RESOURCE_FORBIDDEN, () -> budgets.create(1, PROJECT, body(), "owned"));
        rejected(RESOURCE_FORBIDDEN, () -> budgets.get(1, id(own), null));
        jdbc.update("UPDATE design_result_version SET deleted = FALSE WHERE id = ?", VERSION);
        jdbc.update("UPDATE design_project SET deleted = TRUE WHERE id = ?", PROJECT);
        rejected(RESOURCE_FORBIDDEN, () -> budgets.get(1, id(own), null));
        rejected(RESOURCE_FORBIDDEN, () -> budgets.save(1, id(own), "deleted-project"));
    }

    @Test
    void allHttpEntrypointsRequireUnrestrictedIdentityAndValidateIdAndKeyInputs() throws Exception {
        JsonNode created = create(body(), "for-auth");
        for (String token : List.of("restricted", "invalid")) {
            int expectedCode = "restricted".equals(token) ? IdentitySessionPort.ACCESS_GRANT_REQUIRED : 401;
            assertThat(response(createRequest(body(), "auth-create", token)).path("code").asInt()).isEqualTo(expectedCode);
            for (MockHttpServletRequestBuilder request : List.of(
                    get(BASE + "/budget-estimates/{id}", id(created)),
                    get(BASE + "/design-projects/{id}/budget-estimates", PROJECT),
                    post(BASE + "/budget-estimates/{id}/save", id(created)).header("Idempotency-Key", "auth-save"))) {
                assertThat(response(request.header("Authorization", "Bearer " + token)).path("code").asInt()).isEqualTo(expectedCode);
            }
        }
        assertThat(response(createRequest(body(), "foreign-http", "other")).path("code").asInt()).isEqualTo(RESOURCE_FORBIDDEN.getCode());
        assertThat(response(post(BASE + "/design-projects/{id}/budget-estimates/itemized", PROJECT)
                .header("Authorization", "Bearer owner").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body())))
                .path("code").asInt()).isNotZero();
        for (String malformed : List.of("9223372036854775808", "1.5", "-1", "not-an-id")) {
            assertThat(response(get(BASE + "/budget-estimates/{id}", malformed).header("Authorization", "Bearer owner"))
                    .path("code").asInt()).isEqualTo(BUDGET_INPUT_INVALID.getCode());
        }
        assertThat(count("budget_estimate")).isEqualTo(1);
        assertThat(count("budget_app_command")).isEqualTo(1);
    }

    @Test
    void publicDetailNeverReturnsUnpublishedAdminRevisionEvenWhenCurrentRevisionAdvances() throws Exception {
        JsonNode initial = create(body(), "initial");
        long adminRevision = Long.parseLong(initial.path("revisionId").asText()) + 10;
        new TransactionTemplate(transactions).executeWithoutResult(ignored -> {
            jdbc.update("INSERT INTO budget_revision (id, estimate_id, revision_no, input_snapshot, completeness, body_subtotal_cents, "
                    + "exterior_subtotal_cents, priced_subtotal_cents, total_cents, actor_type, actor_id, change_reason, idempotency_key, request_hash) "
                    + "SELECT ?, estimate_id, 2, '{\"internalNote\":\"future admin secret\"}', completeness, body_subtotal_cents, "
                    + "exterior_subtotal_cents, priced_subtotal_cents, total_cents, 'ADMIN', 99, 'internal draft', 'admin-draft', request_hash "
                    + "FROM budget_revision WHERE id = ?", adminRevision, Long.parseLong(initial.path("revisionId").asText()));
            jdbc.update("UPDATE budget_estimate SET current_revision = 2 WHERE id = ?", id(initial));
        });
        assertThat(ok(get(BASE + "/budget-estimates/{id}", id(initial)).header("Authorization", "Bearer owner"))).isEqualTo(initial);
        assertThat(ok(get(BASE + "/budget-estimates/{id}", id(initial)).param("revisionId", initial.path("revisionId").asText())
                .header("Authorization", "Bearer owner"))).isEqualTo(initial);
        assertThat(response(get(BASE + "/budget-estimates/{id}", id(initial)).param("revisionId", Long.toString(adminRevision))
                .header("Authorization", "Bearer owner")).path("code").asInt()).isNotZero();
        assertThat(tree(budgets.listByProject(1, PROJECT)).toString()).doesNotContain("future admin secret", Long.toString(adminRevision));
    }

    @Test
    void legacyRangeStillUsesItsOldShapeAndMixedHistoryPreservesListAndCursorContracts() throws Exception {
        long rule = legacy.createRuleVersion("TEST_A", "FRAME", "NORMAL", 120_000, 150_000, Instant.parse("2020-01-01T00:00:00Z"));
        var old = legacy.createEstimate(1, PROJECT, "TEST_A", "FRAME", "NORMAL", 240, VERSION);
        JsonNode a = create(body(), "history-a");
        JsonNode b = create(body(), "history-b");
        budgets.save(1, id(a), "save-history-a");
        project(PROJECT + 10, 1, REQUIREMENT + 10, inputs());
        var otherBody = body();
        otherBody.put("resultVersionId", null);
        budgets.create(1, PROJECT + 10, otherBody, "other-project-budget");
        assertThat(budgets.get(1, old.estimateId(), null)).isEmpty();
        JsonNode oldDetail = ok(get(BASE + "/budget-estimates/{id}", old.estimateId()).header("Authorization", "Bearer owner"));
        assertThat(oldDetail.path("estimateId").asText()).isEqualTo(Long.toString(old.estimateId()));
        assertThat(oldDetail.path("ruleVersion").asText()).isEqualTo(Long.toString(rule));
        assertThat(oldDetail.path("totalMinCents").asLong()).isEqualTo(28_800_000L);
        assertThat(oldDetail.path("totalMaxCents").asLong()).isEqualTo(36_000_000L);
        assertThat(oldDetail.has("items")).isFalse();
        JsonNode list = ok(get(BASE + "/design-projects/{id}/budget-estimates", PROJECT).header("Authorization", "Bearer owner"));
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(3);
        assertThat(historyIds(list)).containsExactly(b.path("budgetId").asText(), a.path("budgetId").asText(), Long.toString(old.estimateId()));
        JsonNode first = ok(get(BASE + "/design-projects/{id}/budget-estimates", PROJECT).param("limit", "2")
                .header("Authorization", "Bearer owner"));
        assertThat(fields(first)).containsExactlyInAnyOrder("list", "nextCursor");
        assertThat(historyIds(first.path("list"))).containsExactly(b.path("budgetId").asText(), a.path("budgetId").asText());
        assertThat(first.path("nextCursor").isTextual()).isTrue();
        JsonNode second = ok(get(BASE + "/design-projects/{id}/budget-estimates", PROJECT).param("limit", "2")
                .param("cursor", first.path("nextCursor").asText()).header("Authorization", "Bearer owner"));
        assertThat(historyIds(second.path("list"))).containsExactly(Long.toString(old.estimateId()));
        assertThat(second.path("nextCursor").isNull()).isTrue();
        JsonNode saved = ok(get(BASE + "/design-projects/{id}/budget-estimates", PROJECT).param("savedOnly", "true")
                .header("Authorization", "Bearer owner"));
        assertThat(historyIds(saved.path("list"))).containsExactly(a.path("budgetId").asText());
        assertThat(saved.path("list").get(0).path("totalCents").asLong()).isEqualTo(TOTAL);
    }

    private ItemizedBudgetService service(AuditPort audit) {
        return new ItemizedBudgetService(ds, transactions, projects, audit);
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

    private Map<String, Object> body() {
        var result = new LinkedHashMap<String, Object>();
        result.put("resultVersionId", Long.toString(VERSION));
        result.put("inputOverrides", Map.of());
        result.put("optionIds", OPTIONS);
        return result;
    }

    private Map<String, Object> withOptions(List<String> options) {
        var request = body();
        request.put("optionIds", options);
        return request;
    }

    private List<String> without(String... excluded) {
        return OPTIONS.stream().filter(id -> !List.of(excluded).contains(id)).toList();
    }

    private void project(long id, long userId, long requirementId, Map<String, Object> inputs) {
        jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (?, ?, 'SELF_UPLOAD')", id, userId);
        jdbc.update("INSERT INTO design_requirement_snapshot (id, project_id, input_version, inputs, create_time) "
                + "VALUES (?, ?, 1, CAST(? AS jsonb), '2026-01-01T00:00:00Z')", requirementId, id,
                tree(Map.of("budgetInputs", inputs, "prompt", "private fixture prompt")).toString());
    }

    private void version(long id, long projectId, String config) {
        jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids, config_snapshot, create_time) "
                + "VALUES (?, ?, 1, 1, '[]', CAST(? AS jsonb), '2026-01-02T00:00:00Z')", id, projectId, config);
    }

    private void replaceInputs(Map<String, Object> inputs) {
        jdbc.update("UPDATE design_requirement_snapshot SET inputs = CAST(? AS jsonb) WHERE id = ?",
                tree(Map.of("budgetInputs", inputs, "prompt", "private fixture prompt")).toString(), REQUIREMENT);
    }

    private void price(long id, long regionId, long optionId, long cents) {
        jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id, unit_price_cents, status, effective_at, published_by, published_at) "
                + "VALUES (?, ?, ?, ?, 'PUBLISHED', '2020-01-01T00:00:00Z', 99, now())", id, regionId, optionId, cents);
    }

    private void custom(long itemId, long optionId, boolean publicSelectable, long tenant) {
        jdbc.update("INSERT INTO budget_item (id, code, name, category, source, public_selectable, enabled, tenant_id) "
                + "VALUES (?, ?, '自定义排水附加项', 'EXTERIOR', 'CUSTOM_TEMPLATE', ?, TRUE, ?)", itemId, "CUSTOM_" + itemId, publicSelectable, tenant);
        jdbc.update("INSERT INTO budget_option (id, item_id, code, label, selection_group, unit, quantity_source, enabled, tenant_id) "
                + "VALUES (?, ?, 'FIXED', '已核定排水套餐', 'CUSTOM', 'SET', 'FIXED_ONE', TRUE, ?)", optionId, itemId, tenant);
    }

    private JsonNode create(Map<String, Object> request, String key) {
        return tree(budgets.create(1, PROJECT, request, key));
    }

    private JsonNode tree(Object value) {
        try {
            // Compare the same JSON wire representation as MockMvc responses: valueToTree retains
            // Java LongNode types, while readTree parses small integral JSON values as IntNode.
            return JSON.readTree(JSON.writeValueAsBytes(value));
        } catch (java.io.IOException exception) {
            throw new AssertionError("Could not serialize the test fixture to JSON", exception);
        }
    }

    private JsonNode item(JsonNode result, String code) {
        for (JsonNode item : result.path("items")) if (item.path("itemCode").asText().equals(code)) return item;
        throw new AssertionError("Missing item " + code + ": " + result);
    }

    private void incomplete(JsonNode result, long subtotal) {
        assertThat(result.path("completeness").asText()).isEqualTo("INCOMPLETE");
        assertThat(result.path("totalCents").isNull()).isTrue();
        assertThat(result.path("pricedSubtotalCents").asLong()).isEqualTo(subtotal);
    }

    private long id(JsonNode budget) {
        return Long.parseLong(budget.path("budgetId").asText());
    }

    private int count(String fixtureTable) {
        return jdbc.queryForObject("SELECT count(*) FROM " + fixtureTable, Integer.class);
    }

    private void assertNoWrites() {
        for (String table : List.of("budget_estimate", "budget_revision", "budget_line", "budget_app_command", "audit_event")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private void rejected(ErrorCode error, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(ServiceException.class,
                exception -> assertThat(exception.getCode()).isEqualTo(error.getCode()));
    }

    private Set<String> fields(JsonNode object) {
        var result = new LinkedHashSet<String>();
        object.fieldNames().forEachRemaining(result::add);
        return result;
    }

    private List<String> historyIds(JsonNode list) {
        var result = new ArrayList<String>();
        for (JsonNode budget : list) result.add(budget.has("budgetId") ? budget.path("budgetId").asText() : budget.path("estimateId").asText());
        return result;
    }

    private MockHttpServletRequestBuilder createRequest(Map<String, Object> body, String key, String token) throws Exception {
        return post(BASE + "/design-projects/{id}/budget-estimates/itemized", PROJECT)
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsBytes(body));
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
