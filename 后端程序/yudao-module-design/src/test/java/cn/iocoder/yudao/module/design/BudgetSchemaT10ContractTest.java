package cn.iocoder.yudao.module.design;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.budget.BudgetService;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real PG 17 + Flyway. Every test owns its schema inside the disposable test container. */
@Testcontainers
class BudgetSchemaT10ContractTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String HASH = "a".repeat(64);

    private record Database(SimpleDriverDataSource ds, JdbcTemplate jdbc, String schema) {
        FluentConfiguration migration() {
            return Flyway.configure().dataSource(ds).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration/platform", "classpath:db/migration/design");
        }

        TransactionTemplate transaction() {
            return new TransactionTemplate(new DataSourceTransactionManager(ds));
        }

        BudgetService budgets() {
            var projects = new DesignProjectService(ds, new DataSourceTransactionManager(ds), null, null, null, null);
            return new BudgetService(ds, projects);
        }
    }

    private Database database(boolean legacyOnly) {
        String schema = "budget_t10_" + SEQUENCE.incrementAndGet();
        var ds = new SimpleDriverDataSource(new org.postgresql.Driver(), PG.getJdbcUrl()
                + (PG.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                PG.getUsername(), PG.getPassword());
        var db = new Database(ds, new JdbcTemplate(ds), schema);
        var migration = db.migration();
        if (legacyOnly) {
            migration.target("20260905.206");
        }
        migration.load().migrate();
        return db;
    }

    @Test
    void emptyDatabaseMigratesAndRepeatMigrationDoesNotSeedPricesOrDuplicateCatalog() {
        var db = database(false);
        assertThat(db.migration().load().migrate().migrationsExecuted).isZero();
        db.migration().load().validate();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE category = 'BODY'", Integer.class)).isEqualTo(3);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE category = 'EXTERIOR'", Integer.class)).isEqualTo(7);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE enabled OR public_selectable", Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_option", Integer.class)).isEqualTo(35);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_option WHERE enabled OR source_reference IS NULL", Integer.class)).isZero();
        for (String table : new String[]{"budget_region", "budget_item_price", "budget_estimate", "budget_quote", "budget_catalog_command"}) {
            assertThat(db.jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class)).isZero();
        }
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_item WHERE code = 'RAILING'", Integer.class)).isZero();
    }

    @Test
    void existingLegacyRecordIsUnchangedAndOldInsertShapeStillWorksAfterUpgrade() {
        var db = database(true);
        seedLegacy(db, 91);
        String columns = "id, project_id, user_id, rule_version_id, result_version_id, input_snapshot::text, "
                + "total_low_cents, total_high_cents, disclaimer, tenant_id, creator, create_time, updater, update_time, deleted";
        var before = db.jdbc.queryForMap("SELECT " + columns + " FROM budget_estimate WHERE id = 91");

        db.migration().load().migrate();

        assertThat(db.jdbc.queryForMap("SELECT " + columns + " FROM budget_estimate WHERE id = 91")).isEqualTo(before);
        assertThat(db.jdbc.queryForObject("SELECT model FROM budget_estimate WHERE id = 91", String.class)).isEqualTo("LEGACY_RANGE");
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_revision", Integer.class)).isZero();
        seedLegacy(db, 92); // Models default to legacy so a compatibility release can still accept old writes.
        assertThat(db.budgets().getEstimate(1, 91).orElseThrow().totalLowCents()).isEqualTo(3600000L);
        assertThat(db.budgets().listByProject(1, 100)).hasSize(2);
    }

    @Test
    void modelBoundaryAndDeferredRevisionReferenceRejectInvalidOrPartialRecords() {
        var db = database(false);
        seedLegacy(db, 91);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_estimate SET total_low_cents = NULL WHERE id = 91"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_estimate SET model = 'ITEMIZED_V1' WHERE id = 91"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.transaction().executeWithoutResult(tx -> insertMaster(db, 1000)))
                .isInstanceOf(RuntimeException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_estimate WHERE id = 1000", Integer.class)).isZero();

        createItemized(db, 1000, 2000, true, 12357);
        assertThat(db.budgets().getEstimate(1, 1000)).isEmpty(); // Never decode nullable new totals as a zero legacy range.
        assertThat(db.budgets().listByProject(1, 100)).hasSize(1);
        assertThatThrownBy(() -> createItemized(db, 1001, 2001, true, 12357, "create-1000"))
                .isInstanceOf(DataAccessException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_estimate WHERE id = 1001", Integer.class)).isZero();
    }

    @Test
    void lineStatesDistinguishMissingPriceFromFreeAndEnforcePrecision() {
        var db = database(false);
        createItemized(db, 1000, 2000, false, 0);
        insertLine(db, 1, "METER", "12.3450", 1001L, 12357L, "PRICED", null, null);
        insertLine(db, 2, "METER", "1", null, null, "MISSING_PRICE", null, null);
        insertLine(db, 3, "METER", "1", 0L, 0L, "PRICED", "明确赠送", null);
        insertLine(db, 4, "METER", null, 100L, null, "MISSING_QUANTITY", null, null);
        insertLine(db, 5, "METER", null, null, null, "MISSING_BOTH", null, null);
        insertLine(db, 6, "METER", null, null, null, "EXCLUDED", null, "本次不含此施工");
        assertThat(db.jdbc.queryForObject("SELECT amount_cents FROM budget_line WHERE id = 2", Long.class)).isNull();
        assertThat(db.jdbc.queryForObject("SELECT amount_cents FROM budget_line WHERE id = 3", Long.class)).isZero();

        for (String quantity : new String[]{"0", "-1", "1000000.0001", "1.00001", "NaN", "Infinity"}) {
            assertThatThrownBy(() -> insertLine(db, 10, "METER", quantity, 1L, 1L, "PRICED", null, null))
                    .isInstanceOf(DataAccessException.class);
        }
        assertThatThrownBy(() -> insertLine(db, 10, "PIECE", "1.5", 100L, 150L, "PRICED", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLine(db, 10, "ITEM", "100001", 1L, 100001L, "PRICED", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLine(db, 10, "METER", "1", 0L, 0L, "PRICED", " ", null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLine(db, 10, "METER", "1", null, 0L, "MISSING_PRICE", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLine(db, 10, "METER", "1", 100L, 99L, "PRICED", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertLine(db, 10, "METER", null, null, null, "EXCLUDED", null, null))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_line SET amount_cents = 10000000001 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void lineFailureRollsBackWholeRevisionAndMasterWithoutTouchingHistory() {
        var db = database(false);
        seedLegacy(db, 91);
        assertThatThrownBy(() -> db.transaction().executeWithoutResult(tx -> {
            createItemized(db, 1000, 2000, false, 0);
            insertLine(db, 1, "METER", "1", 100L, 100L, "PRICED", null, null);
            insertLine(db, 1, "METER", "1", 100L, 100L, "PRICED", null, null);
        })).isInstanceOf(DataAccessException.class);
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_revision", Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_line", Integer.class)).isZero();
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_estimate", Integer.class)).isEqualTo(1);
        assertThat(db.budgets().getEstimate(1, 91)).isPresent();
    }

    @Test
    void quoteMustBindCompleteSameBudgetRevisionAndExactCalculatedTotal() {
        var db = database(false);
        createItemized(db, 1000, 2000, false, 48562000);
        assertThatThrownBy(() -> insertQuote(db, 1000, 2000, 48562000, -562000, 48000000))
                .isInstanceOf(DataAccessException.class);
        createItemized(db, 1001, 2001, true, 48562000);
        assertThatThrownBy(() -> insertQuote(db, 1000, 2001, 48562000, -562000, 48000000))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertQuote(db, 1001, 2001, 48562001, -562001, 48000000))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> insertQuote(db, 1001, 2001, 48562000, 0, 48000000))
                .isInstanceOf(DataAccessException.class);
        insertQuote(db, 1001, 2001, 48562000, -562000, 48000000);
        assertThat(db.jdbc.queryForObject("SELECT total_cents FROM budget_revision WHERE id = 2001", Long.class)).isEqualTo(48562000);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_quote SET status = 'PUBLISHED' WHERE id = 3000"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void catalogDefaultsArePrivateAndPricePublicationRequiresKnownPriceAndValidDates() {
        var db = database(false);
        db.jdbc.update("INSERT INTO budget_item (id, code, name, category, source) VALUES (88, 'DRAINAGE', '排水沟施工', 'EXTERIOR', 'CUSTOM_TEMPLATE')");
        assertThat(db.jdbc.queryForObject("SELECT public_selectable FROM budget_item WHERE id = 88", Boolean.class)).isFalse();
        db.jdbc.update("INSERT INTO budget_region (id, code, name) VALUES (1, 'TEST_ONLY', '测试地区')");
        db.jdbc.update("INSERT INTO budget_option (id, item_id, code, label, selection_group, unit, quantity_source, quantity_key) "
                + "VALUES (1, 88, 'STANDARD', '排水沟', 'DRAINAGE', 'METER', 'PROJECT_QUANTITY', 'DRAINAGE_LENGTH')");
        db.jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id) VALUES (1, 1, 1)");
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_item_price SET status = 'PUBLISHED', effective_at = now(), "
                + "published_at = now(), published_by = 9 WHERE id = 1")).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_item_price SET unit_price_cents = 0 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_item_price SET unit_price_cents = -1 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_item_price SET unit_price_cents = 100000001 WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> db.jdbc.update("UPDATE budget_item_price SET effective_at = now(), expires_at = now() WHERE id = 1"))
                .isInstanceOf(DataAccessException.class);
        db.jdbc.update("UPDATE budget_item_price SET unit_price_cents = 18000, status = 'PUBLISHED', effective_at = now(), "
                + "published_at = now(), published_by = 9 WHERE id = 1");
    }

    @Test
    void legacyServiceRejectsForeignDeletedAndMissingProjectsOrResultVersionsWithoutWrites() {
        var db = database(false);
        seedLegacy(db, 91);
        db.jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (101, 2, 'SELF_UPLOAD')");
        db.jdbc.update("INSERT INTO design_result_version (id, project_id, version, flat_selection_id, flat_candidate_ids) "
                + "VALUES (9007199254740993, 101, 1, 1, '[]'), (9007199254740995, 100, 1, 1, '[]')");
        var budgets = db.budgets();
        budgets.createRuleVersion("TEST_ONLY", "BRICK", "A", 30000, 50000, Instant.now().minusSeconds(60));
        assertThatThrownBy(() -> budgets.createEstimate(2, 100, "TEST_ONLY", "BRICK", "A", 120, null))
                .isInstanceOfSatisfying(ServiceException.class, e -> assertThat(e.getCode()).isEqualTo(RESOURCE_FORBIDDEN.getCode()));
        assertThatThrownBy(() -> budgets.createEstimate(1, 999, "TEST_ONLY", "BRICK", "A", 120, null))
                .isInstanceOf(ServiceException.class);
        for (long version : new long[]{9007199254740993L, 999L}) {
            assertThatThrownBy(() -> budgets.createEstimate(1, 100, "TEST_ONLY", "BRICK", "A", 120, version))
                    .isInstanceOf(ServiceException.class);
        }
        assertThat(db.jdbc.queryForObject("SELECT count(*) FROM budget_estimate", Integer.class)).isEqualTo(1);
        var valid = budgets.createEstimate(1, 100, "TEST_ONLY", "BRICK", "A", 120, 9007199254740995L);
        assertThat(db.jdbc.queryForObject("SELECT result_version_id FROM budget_estimate WHERE id = ?", Long.class, valid.estimateId()))
                .isEqualTo(9007199254740995L);
        assertThat(budgets.getEstimate(2, valid.estimateId())).isEmpty();
        db.jdbc.update("UPDATE design_project SET deleted = TRUE WHERE id = 100");
        assertThat(budgets.getEstimate(1, valid.estimateId())).isEmpty();
        assertThatThrownBy(() -> budgets.createEstimate(1, 100, "TEST_ONLY", "BRICK", "A", 120, null))
                .isInstanceOf(ServiceException.class);
    }

    private void seedLegacy(Database db, long id) {
        db.jdbc.update("INSERT INTO design_project (id, user_id, source_type) VALUES (100, 1, 'SELF_UPLOAD') ON CONFLICT DO NOTHING");
        db.jdbc.update("INSERT INTO budget_estimate (id, project_id, user_id, rule_version_id, input_snapshot, total_low_cents, total_high_cents) "
                + "VALUES (?, 100, 1, 123, '{\"buildingArea\":120}', 3600000, 6000000)", id);
    }

    private void insertMaster(Database db, long id) {
        insertMaster(db, id, "create-" + id);
    }

    private void insertMaster(Database db, long id, String key) {
        db.jdbc.update("INSERT INTO budget_estimate (id, project_id, user_id, input_snapshot, model, current_revision, idempotency_key, request_hash) "
                + "VALUES (?, 100, 1, '{}', 'ITEMIZED_V1', 1, ?, ?)", id, key, HASH);
    }

    private void createItemized(Database db, long estimateId, long revisionId, boolean complete, long subtotal) {
        createItemized(db, estimateId, revisionId, complete, subtotal, "create-" + estimateId);
    }

    private void createItemized(Database db, long estimateId, long revisionId, boolean complete, long subtotal, String key) {
        db.transaction().executeWithoutResult(tx -> {
            insertMaster(db, estimateId, key);
            db.jdbc.update("INSERT INTO budget_revision (id, estimate_id, revision_no, input_snapshot, completeness, "
                    + "body_subtotal_cents, exterior_subtotal_cents, priced_subtotal_cents, total_cents, actor_type, actor_id, change_reason, idempotency_key, request_hash) "
                    + "VALUES (?, ?, 1, '{}', ?, ?, 0, ?, ?, 'USER', 1, '测试初始快照', ?, ?)",
                    revisionId, estimateId, complete ? "COMPLETE" : "INCOMPLETE", subtotal, subtotal,
                    complete ? subtotal : null, "revision-" + estimateId, HASH);
        });
    }

    private void insertLine(Database db, long id, String unit, String quantity, Long price, Long amount,
                            String status, String freeReason, String excludedReason) {
        // Text CAST deliberately exercises PG handling of NaN/Infinity as well as decimal precision.
        db.jdbc.update("INSERT INTO budget_line (id, revision_id, line_key, item_code, public_name, category, source, "
                + "unit, quantity, unit_price_cents, amount_cents, status, free_reason, excluded_reason) "
                + "VALUES (?, 2000, ?, 'DRAINAGE', '排水沟施工', 'EXTERIOR', 'PROJECT_CUSTOM', ?, CAST(? AS numeric), ?, ?, ?, ?, ?)",
                id, "line-" + id, unit, quantity, price, amount, status, freeReason, excludedReason);
    }

    private void insertQuote(Database db, long estimateId, long revisionId, long calculated, long adjustment, long finalPrice) {
        db.jdbc.update("INSERT INTO budget_quote (id, estimate_id, revision_id, quote_version, calculated_total_cents, "
                + "adjustment_cents, final_price_cents, reason, actor_id, idempotency_key, request_hash) "
                + "VALUES (3000, ?, ?, 1, ?, ?, ?, '测试商务调整', 9, 'quote-1', ?)",
                estimateId, revisionId, calculated, adjustment, finalPrice, HASH);
    }
}
