package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetCatalogVO.*;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetCatalogRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/** T10 catalog configuration. Prices are explicit drafts; every write and audit share one transaction. */
@Service
public class BudgetCatalogService {
    // Existing Zhongshu design/project/identity data belongs to one company domain, not the platform's login tenants.
    // Request headers must never select another catalog. Admin controller additionally verifies the configured login tenant.
    private static final long BUSINESS_TENANT_ID = 0L;
    private static final Set<String> AREA_SOURCES = Set.of("FOOTPRINT_AREA", "BUILDING_AREA", "ROOF_AREA", "WINDOW_AREA");
    private static final Set<String> UNITS = Set.of("SQM", "METER", "PIECE", "SET", "HOUSEHOLD", "ITEM");
    private static final Map<String, String> QUANTITY_UNITS = Map.of(
            "DOOR_HOUSEHOLDS", "HOUSEHOLD", "WINDOW_AREA", "SQM", "WALL_PAINT_AREA", "SQM",
            "CULTURE_STONE_LENGTH", "METER", "LIGHTING_WASHER_LENGTH", "METER", "LIGHTING_STRIP_LENGTH", "METER",
            "LIGHTING_WALL_LAMP_COUNT", "PIECE", "WATERPROOF_AREA", "SQM", "INSULATED_WATERPROOF_AREA", "SQM");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditPort auditPort;

    public BudgetCatalogService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort auditPort) {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        this.auditPort = auditPort;
    }

    public PageResult<Region> regions(int pageNo, int pageSize) {
        return page("budget_region", "", List.of(), REGION, "code, id", pageNo, pageSize);
    }

    public Region createRegion(long actor, String key, Map<String, Object> body) {
        fields(body, "code", "name", "enabled");
        String code = code(body, "code", 32, "[A-Za-z0-9_-]+"), name = text(body, "name", 100);
        boolean enabled = bool(body, "enabled", false);
        return command(actor, "REGION_CREATE", key, "", body, Region.class, () -> {
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO budget_region (id, code, name, enabled, tenant_id, creator, updater) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    id, code, name, enabled, tenant(), String.valueOf(actor), String.valueOf(actor));
            audit(actor, "CREATE", "budget_region", id, 1);
            return region(id);
        });
    }

    public Region updateRegion(long actor, String key, String idText, Map<String, Object> body) {
        fields(body, "name", "enabled", "expectedVersion");
        long id = BudgetInputs.positiveId(idText);
        String name = text(body, "name", 100);
        boolean enabled = bool(body, "enabled", null);
        int expected = version(body);
        return command(actor, "REGION_UPDATE", key, idText, body, Region.class, () -> {
            checkVersion(lock("budget_region", id), expected);
            jdbc.update("UPDATE budget_region SET name = ?, enabled = ?, version = version + 1, updater = ?, update_time = now() WHERE tenant_id = ? AND id = ?",
                    name, enabled, String.valueOf(actor), tenant(), id);
            audit(actor, "UPDATE", "budget_region", id, expected + 1);
            return region(id);
        });
    }

    public PageResult<Item> items(String category, int pageNo, int pageSize) {
        if (category != null) category(category);
        return page("budget_item", category == null ? "" : " AND category = ?", category == null ? List.of() : List.of(category),
                ITEM, "sort_order, id", pageNo, pageSize);
    }

    public Item createItem(long actor, String key, Map<String, Object> body) {
        fields(body, "code", "name", "category", "enabled", "publicSelectable", "sortOrder");
        String code = code(body, "code", 64, "[A-Z0-9_]+"), name = text(body, "name", 100), category = text(body, "category", 16);
        category(category);
        boolean enabled = bool(body, "enabled", false), publicSelectable = bool(body, "publicSelectable", false);
        int sort = sort(body);
        // New templates have no options or published regional prices yet.
        if (publicSelectable) throw exception(BUDGET_INPUT_INVALID);
        return command(actor, "ITEM_CREATE", key, "", body, Item.class, () -> {
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO budget_item (id, code, name, category, source, enabled, public_selectable, sort_order, tenant_id, creator, updater) "
                    + "VALUES (?, ?, ?, ?, 'CUSTOM_TEMPLATE', ?, FALSE, ?, ?, ?, ?)",
                    id, code, name, category, enabled, sort, tenant(), String.valueOf(actor), String.valueOf(actor));
            audit(actor, "CREATE", "budget_item", id, 1);
            return item(id);
        });
    }

    public Item updateItem(long actor, String key, String idText, Map<String, Object> body) {
        fields(body, "name", "enabled", "publicSelectable", "sortOrder", "expectedVersion");
        long id = BudgetInputs.positiveId(idText);
        String name = text(body, "name", 100);
        boolean enabled = bool(body, "enabled", null), publicSelectable = bool(body, "publicSelectable", null);
        int sort = sort(body), expected = version(body);
        return command(actor, "ITEM_UPDATE", key, idText, body, Item.class, () -> {
            Map<String, Object> previous = lock("budget_item", id);
            checkVersion(previous, expected);
            if (publicSelectable && "CUSTOM_TEMPLATE".equals(previous.get("source")) && !hasPublicCustomPrice(id)) {
                throw exception(BUDGET_INPUT_INVALID);
            }
            jdbc.update("UPDATE budget_item SET name = ?, enabled = ?, public_selectable = ?, sort_order = ?, version = version + 1, updater = ?, update_time = now() "
                    + "WHERE tenant_id = ? AND id = ?", name, enabled, publicSelectable, sort, String.valueOf(actor), tenant(), id);
            audit(actor, "UPDATE", "budget_item", id, expected + 1);
            return item(id);
        });
    }

    public PageResult<Option> options(String itemId, int pageNo, int pageSize) {
        if (itemId == null) return page("budget_option", "", List.of(), OPTION, "item_id, sort_order, id", pageNo, pageSize);
        long id = BudgetInputs.positiveId(itemId);
        item(id);
        return page("budget_option", " AND item_id = ?", List.of(id), OPTION, "sort_order, id", pageNo, pageSize);
    }

    public Option createOption(long actor, String key, Map<String, Object> body) {
        fields(body, "itemId", "code", "label", "selectionGroup", "unit", "quantitySource", "quantityKey", "sourceReference", "enabled", "sortOrder");
        long itemId = BudgetInputs.positiveId(text(body, "itemId", 19));
        String code = code(body, "code", 64, "[A-Z0-9_]+"), label = text(body, "label", 100);
        String group = code(body, "selectionGroup", 64, "[A-Z0-9_]+"), unit = text(body, "unit", 16), source = text(body, "quantitySource", 24);
        String quantityKey = optionalText(body, "quantityKey", 64), reference = optionalText(body, "sourceReference", 500);
        boolean enabled = bool(body, "enabled", false);
        int sort = sort(body);
        validateRule(unit, source, quantityKey);
        return command(actor, "OPTION_CREATE", key, "", body, Option.class, () -> {
            var parent = lock("budget_item", itemId);
            validateStandard(parent, code, group, unit, source, quantityKey);
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO budget_option (id, item_id, code, label, selection_group, unit, quantity_source, quantity_key, source_reference, enabled, sort_order, tenant_id, creator, updater) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, itemId, code, label, group, unit, source, quantityKey, reference, enabled, sort, tenant(), String.valueOf(actor), String.valueOf(actor));
            audit(actor, "CREATE", "budget_option", id, 1);
            return option(id);
        });
    }

    public Option updateOption(long actor, String key, String idText, Map<String, Object> body) {
        fields(body, "label", "selectionGroup", "unit", "quantitySource", "quantityKey", "sourceReference", "enabled", "sortOrder", "expectedVersion");
        long id = BudgetInputs.positiveId(idText);
        String label = text(body, "label", 100), group = code(body, "selectionGroup", 64, "[A-Z0-9_]+");
        String unit = text(body, "unit", 16), source = text(body, "quantitySource", 24);
        String quantityKey = optionalText(body, "quantityKey", 64), reference = optionalText(body, "sourceReference", 500);
        boolean enabled = bool(body, "enabled", null);
        int sort = sort(body), expected = version(body);
        validateRule(unit, source, quantityKey);
        return command(actor, "OPTION_UPDATE", key, idText, body, Option.class, () -> {
            // Match creation's parent -> option lock order to avoid a catalog edit deadlock.
            var previous = option(id);
            var parent = lock("budget_item", Long.parseLong(previous.itemId()));
            checkVersion(lock("budget_option", id), expected);
            validateStandard(parent, previous.code(), group, unit, source, quantityKey);
            boolean ruleChanged = !previous.unit().equals(unit) || !previous.quantitySource().equals(source)
                    || !Objects.equals(previous.quantityKey(), quantityKey) || !previous.selectionGroup().equals(group);
            if (ruleChanged && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM budget_item_price WHERE tenant_id = ? AND option_id = ? AND deleted = FALSE)", Boolean.class, tenant(), id))) {
                // A price cannot silently change from, for example, cents/metre into cents/square-metre.
                throw exception(STATE_VERSION_CONFLICT);
            }
            jdbc.update("UPDATE budget_option SET label = ?, selection_group = ?, unit = ?, quantity_source = ?, quantity_key = ?, source_reference = ?, enabled = ?, sort_order = ?, "
                    + "version = version + 1, updater = ?, update_time = now() WHERE tenant_id = ? AND id = ?",
                    label, group, unit, source, quantityKey, reference, enabled, sort, String.valueOf(actor), tenant(), id);
            audit(actor, "UPDATE", "budget_option", id, expected + 1);
            return option(id);
        });
    }

    public PageResult<Price> prices(String regionCode, String optionId, String status, int pageNo, int pageSize) {
        long regionId = regionByCode(regionCode, false);
        List<Object> args = new ArrayList<>(List.of(regionId));
        String filter = " AND region_id = ?";
        if (optionId != null) { filter += " AND option_id = ?"; args.add(BudgetInputs.positiveId(optionId)); }
        if (status != null) {
            if (!Set.of("DRAFT", "PUBLISHED", "DISABLED").contains(status)) throw exception(BUDGET_INPUT_INVALID);
            filter += " AND status = ?"; args.add(status);
        }
        return page("budget_item_price", filter, args, PRICE, "id DESC", pageNo, pageSize);
    }

    public Price createPrice(long actor, String key, Map<String, Object> body) {
        fields(body, "regionCode", "optionId", "unitPriceCents", "freeReason", "effectiveAt", "expiresAt");
        String regionCode = code(body, "regionCode", 32, "[A-Za-z0-9_-]+");
        long optionId = BudgetInputs.positiveId(text(body, "optionId", 19));
        PriceValues values = priceValues(body);
        return command(actor, "PRICE_CREATE", key, "", body, Price.class, () -> {
            long regionId = regionByCode(regionCode, false);
            lock("budget_option", optionId);
            long id = IdWorker.getId();
            jdbc.update("INSERT INTO budget_item_price (id, region_id, option_id, unit_price_cents, free_reason, effective_at, expires_at, tenant_id, creator, updater) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, regionId, optionId, values.cents(), values.freeReason(), timestamp(values.effective()), timestamp(values.expires()), tenant(), String.valueOf(actor), String.valueOf(actor));
            audit(actor, "CREATE", "budget_item_price", id, 1);
            return price(id);
        });
    }

    public Price updatePrice(long actor, String key, String idText, Map<String, Object> body) {
        fields(body, "unitPriceCents", "freeReason", "effectiveAt", "expiresAt", "expectedVersion");
        long id = BudgetInputs.positiveId(idText);
        int expected = version(body);
        PriceValues values = priceValues(body);
        return command(actor, "PRICE_UPDATE", key, idText, body, Price.class, () -> {
            Map<String, Object> previous = lock("budget_item_price", id);
            checkVersion(previous, expected);
            if (!"DRAFT".equals(previous.get("status"))) throw exception(STATE_VERSION_CONFLICT);
            jdbc.update("UPDATE budget_item_price SET unit_price_cents = ?, free_reason = ?, effective_at = ?, expires_at = ?, version = version + 1, updater = ?, update_time = now() "
                    + "WHERE tenant_id = ? AND id = ?", values.cents(), values.freeReason(), timestamp(values.effective()), timestamp(values.expires()), String.valueOf(actor), tenant(), id);
            audit(actor, "UPDATE", "budget_item_price", id, expected + 1);
            return price(id);
        });
    }

    public Price publishPrice(long actor, String key, String idText, Map<String, Object> body) {
        return transitionPrice(actor, key, idText, body, true);
    }

    public Price disablePrice(long actor, String key, String idText, Map<String, Object> body) {
        return transitionPrice(actor, key, idText, body, false);
    }

    private Price transitionPrice(long actor, String key, String idText, Map<String, Object> body, boolean publish) {
        fields(body, "expectedVersion", "reason");
        long id = BudgetInputs.positiveId(idText);
        int expected = version(body);
        String reason = text(body, "reason", 500);
        return command(actor, publish ? "PRICE_PUBLISH" : "PRICE_DISABLE", key, idText, body, Price.class, () -> {
            Price observed = price(id);
            var region = lock("budget_region", Long.parseLong(observed.regionId()));
            // All publishers for this region serialize before inspecting potentially overlapping rows.
            Map<String, Object> previous = lock("budget_item_price", id);
            checkVersion(previous, expected);
            if (publish) {
                if (!"DRAFT".equals(previous.get("status"))) throw exception(STATE_VERSION_CONFLICT);
                if (!Boolean.TRUE.equals(region.get("enabled")) || previous.get("unit_price_cents") == null || previous.get("effective_at") == null) throw exception(BUDGET_INPUT_INVALID);
                Option selected = option(Long.parseLong(observed.optionId()));
                if (!selected.enabled() || !item(Long.parseLong(selected.itemId())).enabled()) throw exception(BUDGET_INPUT_INVALID);
                Timestamp effective = (Timestamp) previous.get("effective_at"), expires = (Timestamp) previous.get("expires_at");
                boolean overlap = Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM budget_item_price WHERE tenant_id = ? AND region_id = ? AND option_id = ? "
                        + "AND status = 'PUBLISHED' AND deleted = FALSE AND id <> ? AND (expires_at IS NULL OR expires_at > ?) "
                        + "AND (CAST(? AS timestamptz) IS NULL OR effective_at < CAST(? AS timestamptz)))", Boolean.class,
                        tenant(), Long.parseLong(observed.regionId()), Long.parseLong(observed.optionId()), id, effective, expires, expires));
                if (overlap) throw exception(BUDGET_PRICE_CONFLICT);
                jdbc.update("UPDATE budget_item_price SET status = 'PUBLISHED', published_by = ?, published_at = now(), version = version + 1, updater = ?, update_time = now() WHERE tenant_id = ? AND id = ?",
                        actor, String.valueOf(actor), tenant(), id);
            } else {
                if (!"PUBLISHED".equals(previous.get("status"))) throw exception(STATE_VERSION_CONFLICT);
                jdbc.update("UPDATE budget_item_price SET status = 'DISABLED', version = version + 1, updater = ?, update_time = now() WHERE tenant_id = ? AND id = ?",
                        String.valueOf(actor), tenant(), id);
            }
            audit(actor, publish ? "PUBLISH" : "DISABLE", "budget_item_price", id, expected + 1, Map.of("reason", reason));
            return price(id);
        });
    }

    public List<AppBudgetCatalogRespVO.Region> publicRegions() {
        return jdbc.query("SELECT code, name FROM budget_region WHERE tenant_id = ? AND enabled = TRUE AND deleted = FALSE ORDER BY code, id",
                (rs, index) -> new AppBudgetCatalogRespVO.Region(rs.getString("code"), rs.getString("name")), tenant());
    }

    public AppBudgetCatalogRespVO publicOptions(String regionCode) {
        long regionId = regionByCode(regionCode, true);
        var parents = jdbc.query("SELECT * FROM budget_item WHERE tenant_id = ? AND enabled = TRUE AND public_selectable = TRUE AND deleted = FALSE ORDER BY sort_order, id", ITEM, tenant());
        List<AppBudgetCatalogRespVO.Item> result = new ArrayList<>();
        for (Item parent : parents) {
            var children = jdbc.query("SELECT o.id, o.code, o.label, o.selection_group, EXISTS (SELECT 1 FROM budget_item_price p WHERE p.tenant_id = o.tenant_id "
                            + "AND p.region_id = ? AND p.option_id = o.id AND p.status = 'PUBLISHED' AND p.deleted = FALSE AND p.effective_at <= now() "
                            + "AND (p.expires_at IS NULL OR p.expires_at > now())) AS available FROM budget_option o WHERE o.tenant_id = ? AND o.item_id = ? "
                            + "AND o.enabled = TRUE AND o.deleted = FALSE ORDER BY o.sort_order, o.id",
                    (rs, index) -> new AppBudgetCatalogRespVO.Option(rs.getString("id"), rs.getString("code"), rs.getString("label"), rs.getString("selection_group"),
                            rs.getBoolean("available") ? "AVAILABLE" : "MISSING_PRICE"), regionId, tenant(), Long.parseLong(parent.itemId()));
            // Custom templates are opt-in and region-complete. Standard identities remain visible with missing price markers.
            if ("CUSTOM_TEMPLATE".equals(parent.source())) children = children.stream().filter(value -> "AVAILABLE".equals(value.availability())).toList();
            if ("CUSTOM_TEMPLATE".equals(parent.source()) && children.isEmpty()) continue;
            result.add(new AppBudgetCatalogRespVO.Item(parent.itemId(), parent.code(), parent.category(), parent.name(), parent.source(), children));
        }
        return new AppBudgetCatalogRespVO(List.copyOf(result));
    }

    private boolean hasPublicCustomPrice(long itemId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM budget_option o JOIN budget_item_price p ON p.tenant_id = o.tenant_id AND p.option_id = o.id "
                + "JOIN budget_region r ON r.tenant_id = p.tenant_id AND r.id = p.region_id WHERE o.tenant_id = ? AND o.item_id = ? "
                + "AND o.enabled = TRUE AND o.deleted = FALSE AND r.enabled = TRUE AND r.deleted = FALSE AND p.status = 'PUBLISHED' AND p.deleted = FALSE "
                + "AND p.effective_at <= now() AND (p.expires_at IS NULL OR p.expires_at > now()))", Boolean.class, tenant(), itemId));
    }

    private <T> T command(long actor, String operation, String key, String target, Map<String, Object> body, Class<T> responseType, Supplier<T> action) {
        if (actor <= 0) throw exception(RESOURCE_FORBIDDEN);
        if (key == null || key.isBlank() || key.length() > 64 || !key.equals(key.trim())) throw exception(BUDGET_INPUT_INVALID);
        String fingerprint = hash(JsonUtils.toJsonString(Map.of("target", target, "body", new TreeMap<>(body))));
        try {
            return tx.execute(status -> {
                jdbc.update("INSERT INTO budget_catalog_command (tenant_id, actor_id, operation, idempotency_key, request_hash) VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                        tenant(), actor, operation, key, fingerprint);
                Map<String, Object> receipt = jdbc.queryForMap("SELECT request_hash, response::text FROM budget_catalog_command WHERE tenant_id = ? AND actor_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE",
                        tenant(), actor, operation, key);
                if (!fingerprint.equals(receipt.get("request_hash"))) throw exception(IDEMPOTENCY_KEY_REUSED);
                if (receipt.get("response") != null) return JsonUtils.parseObject((String) receipt.get("response"), responseType);
                T response = action.get();
                jdbc.update("UPDATE budget_catalog_command SET response = CAST(? AS jsonb) WHERE tenant_id = ? AND actor_id = ? AND operation = ? AND idempotency_key = ?",
                        JsonUtils.toJsonString(response), tenant(), actor, operation, key);
                return response;
            });
        } catch (DuplicateKeyException duplicate) {
            throw exception(BUDGET_INPUT_INVALID);
        }
    }

    private void audit(long actor, String action, String table, long id, int version) { audit(actor, action, table, id, version, Map.of()); }
    private void audit(long actor, String action, String table, long id, int version, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("version", version);
        auditPort.record(AuditEventMessage.builder().eventType("BUDGET_CATALOG_CHANGED")
                .actorType(AuditEventMessage.ActorType.ADMIN).actorId(String.valueOf(actor)).action(action)
                .bizType(table).bizId(String.valueOf(id)).result(AuditEventMessage.AuditResult.SUCCESS).detail(detail).tenantId(tenant()).build());
    }

    private Map<String, Object> lock(String table, long id) {
        var result = jdbc.queryForList("SELECT * FROM " + table + " WHERE tenant_id = ? AND id = ? AND deleted = FALSE FOR UPDATE", tenant(), id);
        if (result.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return result.get(0);
    }
    private void checkVersion(Map<String, Object> row, int expected) {
        if (((Number) row.get("version")).intValue() != expected) throw exception(STATE_VERSION_CONFLICT);
    }
    private Region region(long id) { return one("budget_region", id, REGION); }
    private Item item(long id) { return one("budget_item", id, ITEM); }
    private Option option(long id) { return one("budget_option", id, OPTION); }
    private Price price(long id) { return one("budget_item_price", id, PRICE); }
    private <T> T one(String table, long id, RowMapper<T> mapper) {
        List<T> rows = jdbc.query("SELECT * FROM " + table + " WHERE tenant_id = ? AND id = ? AND deleted = FALSE", mapper, tenant(), id);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }
    private long regionByCode(String code, boolean enabled) {
        if (code == null || code.length() > 32 || !code.matches("[A-Za-z0-9_-]+")) throw exception(BUDGET_INPUT_INVALID);
        List<Long> ids = jdbc.query("SELECT id FROM budget_region WHERE tenant_id = ? AND code = ? AND deleted = FALSE" + (enabled ? " AND enabled = TRUE" : ""),
                (rs, index) -> rs.getLong("id"), tenant(), code);
        if (ids.isEmpty()) throw exception(BUDGET_INPUT_INVALID);
        return ids.get(0);
    }
    private <T> PageResult<T> page(String table, String filter, List<?> values, RowMapper<T> mapper, String order, int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) throw exception(BUDGET_INPUT_INVALID);
        var args = new ArrayList<Object>(); args.add(tenant()); args.addAll(values);
        String where = " WHERE tenant_id = ? AND deleted = FALSE" + filter;
        Long total = jdbc.queryForObject("SELECT count(*) FROM " + table + where, Long.class, args.toArray());
        args.add(pageSize); args.add((long) (pageNo - 1) * pageSize);
        return new PageResult<>(jdbc.query("SELECT * FROM " + table + where + " ORDER BY " + order + " LIMIT ? OFFSET ?", mapper, args.toArray()), total);
    }
    private static long tenant() { return BUSINESS_TENANT_ID; }
    private static final RowMapper<Region> REGION = (rs, index) -> new Region(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getBoolean("enabled"), rs.getInt("version"));
    private static final RowMapper<Item> ITEM = (rs, index) -> new Item(rs.getString("id"), rs.getString("code"), rs.getString("name"), rs.getString("category"), rs.getString("source"), rs.getBoolean("enabled"), rs.getBoolean("public_selectable"), rs.getInt("sort_order"), rs.getInt("version"));
    private static final RowMapper<Option> OPTION = (rs, index) -> new Option(rs.getString("id"), rs.getString("item_id"), rs.getString("code"), rs.getString("label"), rs.getString("selection_group"), rs.getString("unit"), rs.getString("quantity_source"), rs.getString("quantity_key"), rs.getString("source_reference"), rs.getBoolean("enabled"), rs.getInt("sort_order"), rs.getInt("version"));
    private final RowMapper<Price> PRICE = (rs, index) -> new Price(rs.getString("id"), rs.getString("region_id"), region(rs.getLong("region_id")).code(), rs.getString("option_id"),
            rs.getObject("unit_price_cents") == null ? null : rs.getLong("unit_price_cents"), rs.getString("free_reason"), rs.getString("status"), time(rs, "effective_at"), time(rs, "expires_at"), rs.getInt("version"), rs.getString("published_by"), time(rs, "published_at"));
    private static String time(ResultSet rs, String field) throws SQLException { Timestamp value = rs.getTimestamp(field); return value == null ? null : value.toInstant().toString(); }
    private static Timestamp timestamp(Instant value) { return value == null ? null : Timestamp.from(value); }

    private static void fields(Map<String, Object> body, String... allowed) {
        if (body == null || !Set.of(allowed).containsAll(body.keySet())) throw exception(BUDGET_INPUT_INVALID);
    }
    private static String text(Map<String, Object> body, String key, int max) {
        Object value = body.get(key);
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.trim()) || result.length() > max) throw exception(BUDGET_INPUT_INVALID);
        return result;
    }
    private static String optionalText(Map<String, Object> body, String key, int max) { return body.get(key) == null ? null : text(body, key, max); }
    private static String code(Map<String, Object> body, String key, int max, String regex) {
        String result = text(body, key, max);
        if (!result.matches(regex)) throw exception(BUDGET_INPUT_INVALID);
        return result;
    }
    private static boolean bool(Map<String, Object> body, String key, Boolean fallback) {
        if (!body.containsKey(key) && fallback != null) return fallback;
        if (!(body.get(key) instanceof Boolean result)) throw exception(BUDGET_INPUT_INVALID);
        return result;
    }
    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte || value instanceof java.math.BigInteger)) throw exception(BUDGET_INPUT_INVALID);
        java.math.BigInteger result = new java.math.BigInteger(value.toString());
        if (result.compareTo(java.math.BigInteger.valueOf(min)) < 0 || result.compareTo(java.math.BigInteger.valueOf(max)) > 0) throw exception(BUDGET_INPUT_INVALID);
        return result.longValueExact();
    }
    private static int version(Map<String, Object> body) { return (int) integer(body.get("expectedVersion"), 1, Integer.MAX_VALUE - 1); }
    private static int sort(Map<String, Object> body) { return body.containsKey("sortOrder") ? (int) integer(body.get("sortOrder"), 0, 100000) : 0; }
    private static void category(String value) { if (!Set.of("BODY", "EXTERIOR").contains(value)) throw exception(BUDGET_INPUT_INVALID); }
    private record PriceValues(Long cents, String freeReason, Instant effective, Instant expires) {}
    private static PriceValues priceValues(Map<String, Object> body) {
        if (!body.containsKey("unitPriceCents")) throw exception(BUDGET_INPUT_INVALID);
        Long cents = body.get("unitPriceCents") == null ? null : integer(body.get("unitPriceCents"), 0, 100000000);
        String free = optionalText(body, "freeReason", 500);
        if (Objects.equals(cents, 0L) ? free == null : free != null) throw exception(BUDGET_INPUT_INVALID);
        Instant effective = instant(body, "effectiveAt"), expires = instant(body, "expiresAt");
        if (expires != null && (effective == null || !expires.isAfter(effective))) throw exception(BUDGET_INPUT_INVALID);
        return new PriceValues(cents, free, effective, expires);
    }
    private static Instant instant(Map<String, Object> body, String key) {
        String value = optionalText(body, key, 40);
        if (value == null) return null;
        try {
            Instant result = Instant.parse(value);
            if (result.isBefore(Instant.parse("2000-01-01T00:00:00Z")) || result.isAfter(Instant.parse("2100-01-01T00:00:00Z")) || result.getNano() % 1000 != 0) throw exception(BUDGET_INPUT_INVALID);
            return result;
        } catch (DateTimeParseException invalid) { throw exception(BUDGET_INPUT_INVALID); }
    }
    private static void validateRule(String unit, String source, String quantityKey) {
        if (!UNITS.contains(unit)) throw exception(BUDGET_INPUT_INVALID);
        if (AREA_SOURCES.contains(source)) {
            if (!"SQM".equals(unit) || quantityKey != null) throw exception(BUDGET_INPUT_INVALID);
        } else if ("PROJECT_QUANTITY".equals(source)) {
            if (quantityKey == null || !unit.equals(QUANTITY_UNITS.get(quantityKey))) throw exception(BUDGET_INPUT_INVALID);
        } else if ("FIXED_ONE".equals(source)) {
            if (!Set.of("ITEM", "SET").contains(unit) || quantityKey != null) throw exception(BUDGET_INPUT_INVALID);
        } else throw exception(BUDGET_INPUT_INVALID);
    }
    private static void validateStandard(Map<String, Object> parent, String code, String group, String unit, String source, String key) {
        if (!"STANDARD".equals(parent.get("source"))) return;
        String rule = switch ((String) parent.get("code")) {
            case "FOUNDATION" -> Set.of("STRIP", "INDEPENDENT", "RAFT", "PILE").contains(code) ? "FOUNDATION|SQM|FOOTPRINT_AREA|" : "";
            case "STRUCTURE" -> Set.of("BRICK", "FRAME", "FRAME_SHEAR").contains(code) ? "STRUCTURE|SQM|BUILDING_AREA|" : "";
            case "ROOF" -> Set.of("WOOD", "CAST_SINGLE", "CAST_DOUBLE", "FLAT").contains(code) ? "ROOF|SQM|ROOF_AREA|" : "";
            case "DECORATION" -> Set.of("MODERN", "SU", "CHINESE").contains(code) ? "DECORATION|SQM|BUILDING_AREA|" : "";
            case "DOORS_WINDOWS" -> Set.of("ALUMINUM", "CARVED", "STEEL_COPPER", "COPPER").contains(code) ? "DOOR|HOUSEHOLD|PROJECT_QUANTITY|DOOR_HOUSEHOLDS"
                    : Set.of("ALU_14", "ALU_18", "SYSTEM_20").contains(code) ? "WINDOW|SQM|WINDOW_AREA|" : "";
            case "WALL_PAINT" -> Set.of("STONE_PAINT", "SAND_BLACK").contains(code) ? "WALL_PAINT|SQM|BUILDING_AREA|"
                    : Set.of("SAND_GROOVE", "SAND_POLISHED").contains(code) ? "WALL_PAINT|SQM|PROJECT_QUANTITY|WALL_PAINT_AREA" : "";
            case "CULTURE_STONE" -> Set.of("SHALE", "ARTIFICIAL", "NATURAL").contains(code) ? "CULTURE_STONE|METER|PROJECT_QUANTITY|CULTURE_STONE_LENGTH" : "";
            case "LIGHTING" -> switch (code) { case "WASHER" -> "WASHER|METER|PROJECT_QUANTITY|LIGHTING_WASHER_LENGTH"; case "STRIP" -> "STRIP|METER|PROJECT_QUANTITY|LIGHTING_STRIP_LENGTH"; case "WALL_LAMP" -> "WALL_LAMP|PIECE|PROJECT_QUANTITY|LIGHTING_WALL_LAMP_COUNT"; default -> ""; };
            case "WATERPROOF_LIGHTNING" -> switch (code) { case "COATING" -> "WATERPROOF|SQM|PROJECT_QUANTITY|WATERPROOF_AREA"; case "INSULATED" -> "WATERPROOF|SQM|PROJECT_QUANTITY|INSULATED_WATERPROOF_AREA"; case "STANDARD" -> "LIGHTNING|SET|FIXED_ONE|"; default -> ""; };
            case "INSURANCE" -> "STANDARD".equals(code) ? "INSURANCE|SET|FIXED_ONE|" : "";
            default -> "";
        };
        if (!rule.equals(group + "|" + unit + "|" + source + "|" + (key == null ? "" : key))) throw exception(BUDGET_INPUT_INVALID);
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
}
