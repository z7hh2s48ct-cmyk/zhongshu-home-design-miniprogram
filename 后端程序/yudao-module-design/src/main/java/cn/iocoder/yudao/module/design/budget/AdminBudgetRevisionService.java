package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetRevisionVO.*;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/** T10-07: append-only administrator revisions around the existing exact calculator and frozen inputs. */
@Service
public class AdminBudgetRevisionService {
    private static final List<String> STANDARD_CODES = List.of("FOUNDATION", "STRUCTURE", "ROOF", "DECORATION",
            "DOORS_WINDOWS", "WALL_PAINT", "CULTURE_STONE", "LIGHTING", "WATERPROOF_LIGHTNING", "INSURANCE");
    private static final Map<String, Set<String>> REQUIRED_GROUPS = Map.of(
            "DOORS_WINDOWS", Set.of("DOOR", "WINDOW"), "WATERPROOF_LIGHTNING", Set.of("WATERPROOF", "LIGHTNING"));
    private static final List<String> INPUT_FIELDS = List.of("regionCode", "footprintArea", "floorCount", "buildingArea", "roofArea");
    private static final List<String> SNAPSHOT_FIELDS = List.of("importedValues", "importedSources", "inputOverrides",
            "currentValues", "currentSources", "quantities", "requirementSnapshotIds");
    private static final String VISIBLE_PROJECT = " AND EXISTS (SELECT 1 FROM design_project p WHERE p.tenant_id = 0 AND p.id = b.project_id AND p.user_id = b.user_id AND p.deleted = FALSE)"
            + " AND (b.result_version_id IS NULL OR EXISTS (SELECT 1 FROM design_result_version v WHERE v.tenant_id = 0 AND v.id = b.result_version_id AND v.project_id = b.project_id AND v.deleted = FALSE))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditPort audit;

    public AdminBudgetRevisionService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort audit) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.audit = audit;
    }

    private record FrozenLine(BudgetCalculator.LineInput input, String lineKey, Long priceVersionId,
                              Map<String, Object> pricing, String internalNote, int sortOrder) {}
    private record RevisionRequest(int expectedVersion, String reason, List<Map<String, Object>> updates,
                                   List<Map<String, Object>> additions, List<String> removals) {}

    public PageResult<Summary> list(String projectId, String completeness, String keyword, int pageNo, int pageSize) {
        if (pageNo < 1 || pageSize < 1 || pageSize > 100) invalid();
        Long project = projectId == null ? null : BudgetInputs.positiveId(projectId);
        if (completeness != null && !Set.of("COMPLETE", "INCOMPLETE").contains(completeness)) invalid();
        return transaction(() -> {
            List<Object> args = new ArrayList<>();
            String where = " WHERE b.tenant_id = 0 AND b.model = 'ITEMIZED_V1' AND b.deleted = FALSE AND r.tenant_id = 0 AND r.deleted = FALSE" + VISIBLE_PROJECT;
            if (project != null) { where += " AND b.project_id = ?"; args.add(project); }
            if (completeness != null) { where += " AND r.completeness = ?"; args.add(completeness); }
            // E-4 模糊搜索：项目名 / 用户昵称；keyword 为纯数字时同时精确匹配项目编号。
            // JOIN 仅在 keyword 存在时追加：合同测试夹具无 account 表，无条件 JOIN 会破坏基础读契约
            String from = " FROM budget_estimate b JOIN budget_revision r ON r.estimate_id = b.id AND r.revision_no = b.current_revision";
            if (keyword != null && !keyword.isBlank()) {
                String value = keyword.trim();
                where += " AND (bp.name ILIKE ? OR ba.nickname ILIKE ?";
                args.add("%" + value + "%");
                args.add("%" + value + "%");
                if (value.matches("\\d{1,20}")) {
                    where += " OR b.project_id = ?";
                    args.add(Long.parseLong(value));
                }
                where += ")";
                from += " JOIN design_project bp ON bp.id = b.project_id AND bp.deleted = FALSE "
                        + "LEFT JOIN account ba ON ba.id = bp.user_id AND ba.deleted = FALSE";
            }
            Long total = jdbc.queryForObject("SELECT count(*)" + from + where, Long.class, args.toArray());
            args.add(pageSize); args.add((long) (pageNo - 1) * pageSize);
            var rows = jdbc.queryForList("SELECT b.id" + from + where + " ORDER BY b.id DESC LIMIT ? OFFSET ?", args.toArray());
            List<Summary> result = rows.stream().map(row -> summary(detail(requireBudget(number(row, "id"), false), null))).toList();
            return new PageResult<>(result, total);
        });
    }

    public Detail get(String budgetId, String revisionId) {
        long id = BudgetInputs.positiveId(budgetId);
        Long revision = revisionId == null ? null : BudgetInputs.positiveId(revisionId);
        return transaction(() -> detail(requireBudget(id, false), revision));
    }

    public List<RevisionRef> history(String budgetId) {
        long id = BudgetInputs.positiveId(budgetId);
        return transaction(() -> {
            requireBudget(id, false);
            return jdbc.query("SELECT * FROM budget_revision WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE ORDER BY revision_no DESC",
                    (rs, index) -> new RevisionRef(rs.getString("id"), rs.getInt("revision_no"), rs.getString("actor_type"), rs.getString("actor_id"),
                            rs.getString("change_reason"), rs.getString("completeness"), rs.getLong("priced_subtotal_cents"),
                            rs.getObject("total_cents") == null ? null : rs.getLong("total_cents"), rs.getTimestamp("create_time").toInstant().toString()), id);
        });
    }

    public Detail revise(long actor, String budgetId, String key, Map<String, Object> body) {
        long id = BudgetInputs.positiveId(budgetId);
        RevisionRequest request = parseRequest(body);
        return command(actor, "REVISION_CREATE", id, key, body, Detail.class, () -> {
            Map<String, Object> budget = requireBudget(id, true);
            checkVersion(budget, request.expectedVersion());
            Map<String, Object> previous = revision(id, null, request.expectedVersion());
            Map<String, Object> snapshot = json(previous.get("input_snapshot"));
            BudgetInputs.Resolved inputs = frozenInputs(snapshot);
            var lines = new LinkedHashMap<String, FrozenLine>();
            loadLines(number(previous, "id")).forEach(line -> lines.put(line.input().lineId(), line));
            Set<String> touched = new HashSet<>();
            for (String remove : request.removals()) {
                if (!touched.add(remove)) invalid();
                FrozenLine line = ownedLine(lines, remove);
                if ("STANDARD".equals(line.input().source())) invalid();
                lines.remove(remove);
            }
            Timestamp measuredAt = jdbc.queryForObject("SELECT now()", Timestamp.class);
            for (var update : request.updates()) {
                String lineId = id(update, "lineId");
                if (!touched.add(lineId)) invalid();
                FrozenLine old = ownedLine(lines, lineId);
                FrozenLine selected = old;
                if (update.containsKey("optionId")) {
                    if (!"STANDARD".equals(old.input().source()) || old.input().optionId() != null) invalid();
                    selected = catalogLine(id(update, "optionId"), inputs, snapshot, measuredAt, old);
                }
                lines.put(lineId, patch(selected, update));
            }
            Set<String> clientKeys = new HashSet<>();
            int sort = lines.values().stream().mapToInt(FrozenLine::sortOrder).max().orElse(0) + 1;
            for (var addition : request.additions()) {
                String clientKey = code(addition, "clientKey", 64, "[A-Za-z0-9_-]+");
                if (!clientKeys.add(clientKey)) invalid();
                FrozenLine added;
                if ("PROJECT_CUSTOM".equals(addition.get("kind"))) {
                    String lineId = String.valueOf(IdWorker.getId());
                    var input = new BudgetCalculator.LineInput(lineId, null, code(addition, "itemCode", 64, "[A-Z0-9_]+"),
                            text(addition, "category", 16), text(addition, "publicName", 100), null, null,
                            text(addition, "unit", 16), quantity(addition.get("quantity")), cents(addition.get("unitPriceCents")),
                            optionalText(addition, "freeReason", 500), optionalText(addition, "excludedReason", 500), "PROJECT_CUSTOM");
                    Map<String, Object> pricing = new LinkedHashMap<>();
                    pricing.put("originalQuantity", input.quantity()); pricing.put("originalUnitPriceCents", input.unitPriceCents());
                    pricing.put("resolvedQuantitySource", "PROJECT_CUSTOM"); pricing.put("priceSource", "PROJECT_CUSTOM");
                    pricing.put("clientKey", clientKey);
                    added = new FrozenLine(input, "CUSTOM_" + lineId, null, pricing, optionalText(addition, "internalNote", 500), sort++);
                } else {
                    added = catalogLine(id(addition, "optionId"), inputs, snapshot, measuredAt, null);
                    added.pricing().put("clientKey", clientKey);
                    added = new FrozenLine(added.input(), added.lineKey(), added.priceVersionId(), added.pricing(), added.internalNote(), sort++);
                    added = patch(added, addition);
                }
                lines.put(added.input().lineId(), added);
            }
            if (lines.size() > 300) invalid();
            validateLines(lines.values());
            List<FrozenLine> next = lines.values().stream().sorted(Comparator.comparingInt(FrozenLine::sortOrder))
                    .map(AdminBudgetRevisionService::newIdentity).toList();
            BudgetCalculator.Result result = BudgetCalculator.calculate(inputs, next.stream().map(FrozenLine::input).toList());
            long revisionId = IdWorker.getId();
            int nextVersion = request.expectedVersion() + 1;
            // Input provenance remains exactly the user's frozen measurement; only line snapshots change.
            jdbc.update("INSERT INTO budget_revision (id, estimate_id, revision_no, input_snapshot, completeness, body_subtotal_cents, exterior_subtotal_cents, priced_subtotal_cents, total_cents, "
                            + "actor_type, actor_id, change_reason, idempotency_key, request_hash, tenant_id, creator, updater, create_time) VALUES (?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, 'ADMIN', ?, ?, ?, ?, 0, ?, ?, ?)",
                    revisionId, id, nextVersion, jsonString(snapshot), result.completeness(), result.categoryTotals().get("BODY"), result.categoryTotals().get("EXTERIOR"),
                    result.pricedSubtotalCents(), result.totalCents(), actor, request.reason(), key, fingerprint(id, body), String.valueOf(actor), String.valueOf(actor), measuredAt);
            Map<String, BudgetCalculator.LineResult> calculated = new HashMap<>();
            result.lines().forEach(line -> calculated.put(line.input().lineId(), line));
            for (FrozenLine line : next) insertLine(actor, revisionId, line, calculated.get(line.input().lineId()), measuredAt);
            jdbc.update("UPDATE budget_estimate SET current_revision = ?, updater = ?, update_time = now() WHERE tenant_id = 0 AND id = ?", nextVersion, String.valueOf(actor), id);
            audit(actor, "BUDGET_ESTIMATE_CHANGED", "REVISE", "budget_estimate", id,
                    Map.of("revisionId", String.valueOf(revisionId), "previousRevisionId", String.valueOf(number(previous, "id")),
                            "version", nextVersion, "reason", request.reason(), "completeness", result.completeness()));
            return detail(requireBudget(id, false), revisionId);
        });
    }

    public TemplateResult saveAsTemplate(long actor, String budgetId, String lineId, String key, Map<String, Object> body) {
        long budget = BudgetInputs.positiveId(budgetId), line = BudgetInputs.positiveId(lineId);
        fields(body, "expectedVersion", "code", "name", "reason");
        int expected = version(body);
        String code = code(body, "code", 64, "[A-Z0-9_]+"), name = text(body, "name", 100), reason = text(body, "reason", 500);
        var request = new LinkedHashMap<>(body); request.put("lineId", lineId);
        return command(actor, "LINE_SAVE_TEMPLATE", budget, key, request, TemplateResult.class, () -> {
            Map<String, Object> master = requireBudget(budget, true);
            checkVersion(master, expected);
            Map<String, Object> revision = revision(budget, null, expected);
            var rows = jdbc.queryForList("SELECT * FROM budget_line WHERE tenant_id = 0 AND revision_id = ? AND id = ? AND deleted = FALSE", number(revision, "id"), line);
            if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
            if (!"PROJECT_CUSTOM".equals(rows.get(0).get("source"))) invalid();
            long itemId = IdWorker.getId();
            // A project metre quantity is not a general catalog quantity rule. Save identity only, disabled and private.
            jdbc.update("INSERT INTO budget_item (id, code, name, category, source, enabled, public_selectable, tenant_id, creator, updater) "
                            + "VALUES (?, ?, ?, ?, 'CUSTOM_TEMPLATE', FALSE, FALSE, 0, ?, ?)",
                    itemId, code, name, rows.get(0).get("category"), String.valueOf(actor), String.valueOf(actor));
            audit(actor, "BUDGET_CATALOG_CHANGED", "SAVE_TEMPLATE", "budget_item", itemId,
                    Map.of("budgetId", budgetId, "revisionId", String.valueOf(number(revision, "id")), "lineId", lineId, "reason", reason, "version", 1));
            return new TemplateResult(String.valueOf(itemId), code, name, false, false);
        });
    }

    private FrozenLine catalogLine(String optionId, BudgetInputs.Resolved inputs, Map<String, Object> snapshot,
                                   Timestamp measuredAt, FrozenLine placeholder) {
        Object regionCode = snapshot.get("regionCode");
        var regions = jdbc.queryForList("SELECT * FROM budget_region WHERE tenant_id = 0 AND code = ? AND enabled = TRUE AND deleted = FALSE FOR SHARE", regionCode);
        if (regions.isEmpty()) invalid();
        Map<String, Object> region = regions.get(0);
        // Match the existing region -> item -> option -> price lock order. Lookups never choose another business tenant.
        var observed = jdbc.queryForList("SELECT item_id FROM budget_option WHERE tenant_id = 0 AND id = ? AND deleted = FALSE", BudgetInputs.positiveId(optionId));
        if (observed.isEmpty()) invalid();
        var items = jdbc.queryForList("SELECT * FROM budget_item WHERE tenant_id = 0 AND id = ? AND enabled = TRUE AND deleted = FALSE FOR SHARE", number(observed.get(0), "item_id"));
        if (items.isEmpty()) invalid();
        Map<String, Object> item = items.get(0);
        var options = jdbc.queryForList("SELECT * FROM budget_option WHERE tenant_id = 0 AND id = ? AND item_id = ? AND enabled = TRUE AND deleted = FALSE FOR SHARE", Long.parseLong(optionId), number(item, "id"));
        if (options.isEmpty()) invalid();
        Map<String, Object> option = options.get(0);
        String group = (String) option.get("selection_group");
        if (placeholder == null) {
            if (!"CUSTOM_TEMPLATE".equals(item.get("source"))) invalid();
        } else {
            if (!"STANDARD".equals(item.get("source")) || !placeholder.input().itemId().equals(String.valueOf(number(item, "id")))) invalid();
            String required = (String) placeholder.pricing().get("selectionGroup");
            if (!"ITEM".equals(required) && !Objects.equals(required, group)) invalid();
        }
        var prices = jdbc.queryForList("SELECT * FROM budget_item_price WHERE tenant_id = 0 AND region_id = ? AND option_id = ? AND status = 'PUBLISHED' AND deleted = FALSE "
                        + "AND effective_at <= ? AND (expires_at IS NULL OR expires_at > ?) ORDER BY id FOR SHARE",
                number(region, "id"), Long.parseLong(optionId), measuredAt, measuredAt);
        if (prices.size() > 1) throw exception(BUDGET_PRICE_CONFLICT);
        Map<String, Object> price = prices.isEmpty() ? Map.of() : prices.get(0);
        var resolved = BudgetCalculator.resolveQuantity(inputs, (String) option.get("unit"), (String) option.get("quantity_source"), (String) option.get("quantity_key"));
        String lineId = placeholder == null ? String.valueOf(IdWorker.getId()) : placeholder.input().lineId();
        var input = new BudgetCalculator.LineInput(lineId, String.valueOf(number(item, "id")),
                placeholder == null ? (String) item.get("code") : placeholder.input().itemCode(),
                placeholder == null ? (String) item.get("category") : placeholder.input().category(),
                placeholder == null ? (String) item.get("name") : placeholder.input().publicName(), optionId, (String) option.get("label"),
                (String) option.get("unit"), resolved.value(), nullableNumber(price, "unit_price_cents"), (String) price.get("free_reason"),
                placeholder == null ? null : placeholder.input().excludedReason(), (String) item.get("source"));
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("regionId", String.valueOf(number(region, "id"))); pricing.put("regionVersion", region.get("version"));
        pricing.put("itemVersion", item.get("version")); pricing.put("optionCode", option.get("code")); pricing.put("optionVersion", option.get("version"));
        pricing.put("selectionGroup", group); pricing.put("quantitySource", option.get("quantity_source")); pricing.put("quantityKey", option.get("quantity_key"));
        pricing.put("resolvedQuantitySource", resolved.source()); pricing.put("sourceReference", option.get("source_reference"));
        pricing.put("priceVersion", price.get("version")); pricing.put("effectiveAt", instant(price.get("effective_at"))); pricing.put("expiresAt", instant(price.get("expires_at")));
        pricing.put("originalQuantity", placeholder == null ? input.quantity() : placeholder.pricing().get("originalQuantity"));
        pricing.put("originalUnitPriceCents", placeholder == null ? input.unitPriceCents() : placeholder.pricing().get("originalUnitPriceCents"));
        pricing.put("selectedByAdmin", true);
        if (placeholder != null) pricing.put("previousSelection", placeholder.pricing());
        return new FrozenLine(input, placeholder == null ? "OPTION_" + optionId : placeholder.lineKey(), nullableNumber(price, "id"), pricing,
                placeholder == null ? null : placeholder.internalNote(), placeholder == null ? 0 : placeholder.sortOrder());
    }

    private static FrozenLine patch(FrozenLine previous, Map<String, Object> change) {
        var old = previous.input();
        String quantity = change.containsKey("quantity") ? quantity(change.get("quantity")) : old.quantity();
        Long price = change.containsKey("unitPriceCents") ? cents(change.get("unitPriceCents")) : old.unitPriceCents();
        var input = new BudgetCalculator.LineInput(old.lineId(), old.itemId(), old.itemCode(), old.category(), old.publicName(), old.optionId(), old.optionLabel(), old.unit(),
                quantity, price, change.containsKey("freeReason") ? optionalText(change, "freeReason", 500) : old.freeReason(),
                change.containsKey("excludedReason") ? optionalText(change, "excludedReason", 500) : old.excludedReason(), old.source());
        Map<String, Object> pricing = new LinkedHashMap<>(previous.pricing());
        if (change.containsKey("quantity")) pricing.put("adminQuantitySource", "ADMIN_OVERRIDE");
        if (change.containsKey("unitPriceCents")) pricing.put("adminPriceSource", "ADMIN_OVERRIDE");
        return new FrozenLine(input, previous.lineKey(), previous.priceVersionId(), pricing,
                change.containsKey("internalNote") ? optionalText(change, "internalNote", 500) : previous.internalNote(), previous.sortOrder());
    }

    private static void validateLines(Collection<FrozenLine> lines) {
        Map<String, Set<String>> standards = new HashMap<>();
        Set<String> groups = new HashSet<>(), keys = new HashSet<>();
        for (var line : lines) {
            var input = line.input();
            if (!keys.add(line.lineKey())) invalid();
            if (Objects.equals(input.unitPriceCents(), 0L) ? input.freeReason() == null : input.freeReason() != null) invalid();
            if (input.optionId() == null && !"PROJECT_CUSTOM".equals(input.source())
                    && (input.quantity() != null || input.unitPriceCents() != null)) invalid();
            if (("FIXED_ONE".equals(line.pricing().get("quantitySource")) || ("PROJECT_CUSTOM".equals(input.source()) && "ITEM".equals(input.unit())))
                    && input.quantity() != null && new BigDecimal(input.quantity()).compareTo(BigDecimal.ONE) != 0) invalid();
            String group = (String) line.pricing().get("selectionGroup");
            if (input.itemId() != null && (group == null || !groups.add(input.itemId() + ":" + group))) invalid();
            if ("STANDARD".equals(input.source())) standards.computeIfAbsent(input.itemCode(), ignored -> new HashSet<>()).add(group);
        }
        // A removed line or omitted required subgroup may never turn missing work into a complete budget.
        if (!standards.keySet().containsAll(STANDARD_CODES)) invalid();
        REQUIRED_GROUPS.forEach((code, required) -> { if (!standards.get(code).containsAll(required)) invalid(); });
    }

    private List<FrozenLine> loadLines(long revisionId) {
        return jdbc.queryForList("SELECT * FROM budget_line WHERE tenant_id = 0 AND revision_id = ? AND deleted = FALSE ORDER BY sort_order, id", revisionId).stream().map(row -> {
            String quantity = decimal(row.get("quantity"));
            Long price = nullableNumber(row, "unit_price_cents");
            Map<String, Object> pricing = new LinkedHashMap<>(json(row.get("pricing_snapshot")));
            if (!pricing.containsKey("originalQuantity")) pricing.put("originalQuantity", quantity);
            if (!pricing.containsKey("originalUnitPriceCents")) pricing.put("originalUnitPriceCents", price);
            if (!pricing.containsKey("originLineId")) pricing.put("originLineId", String.valueOf(number(row, "id")));
            var input = new BudgetCalculator.LineInput(String.valueOf(number(row, "id")), idValue(row.get("item_id")), (String) row.get("item_code"),
                    (String) row.get("category"), (String) row.get("public_name"), idValue(row.get("option_id")), (String) row.get("option_label"),
                    (String) row.get("unit"), quantity, price, (String) row.get("free_reason"), (String) row.get("excluded_reason"), (String) row.get("source"));
            return new FrozenLine(input, (String) row.get("line_key"), nullableNumber(row, "price_version_id"), pricing, (String) row.get("internal_note"), ((Number) row.get("sort_order")).intValue());
        }).toList();
    }

    private static FrozenLine newIdentity(FrozenLine previous) {
        var old = previous.input();
        var input = new BudgetCalculator.LineInput(String.valueOf(IdWorker.getId()), old.itemId(), old.itemCode(), old.category(), old.publicName(), old.optionId(),
                old.optionLabel(), old.unit(), old.quantity(), old.unitPriceCents(), old.freeReason(), old.excludedReason(), old.source());
        Map<String, Object> pricing = new LinkedHashMap<>(previous.pricing()); pricing.put("previousLineId", old.lineId());
        return new FrozenLine(input, previous.lineKey(), previous.priceVersionId(), pricing, previous.internalNote(), previous.sortOrder());
    }

    private void insertLine(long actor, long revisionId, FrozenLine frozen, BudgetCalculator.LineResult calculated, Timestamp createdAt) {
        var line = frozen.input();
        jdbc.update("INSERT INTO budget_line (id, revision_id, line_key, item_id, option_id, price_version_id, item_code, public_name, option_label, category, source, unit, quantity, unit_price_cents, amount_cents, "
                        + "status, free_reason, excluded_reason, internal_note, pricing_snapshot, sort_order, tenant_id, creator, updater, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 0, ?, ?, ?)",
                Long.parseLong(line.lineId()), revisionId, frozen.lineKey(), longId(line.itemId()), longId(line.optionId()), frozen.priceVersionId(),
                line.itemCode(), line.publicName(), line.optionLabel(), line.category(), line.source(), line.unit(),
                line.quantity() == null ? null : new BigDecimal(line.quantity()), line.unitPriceCents(), calculated.amountCents(), calculated.status(),
                line.freeReason(), line.excludedReason(), frozen.internalNote(), jsonString(frozen.pricing()), frozen.sortOrder(), String.valueOf(actor), String.valueOf(actor), createdAt);
    }

    private Detail detail(Map<String, Object> budget, Long selectedRevisionId) {
        int current = ((Number) budget.get("current_revision")).intValue();
        Map<String, Object> revision = revision(number(budget, "id"), selectedRevisionId, current);
        Map<String, Object> snapshot = json(revision.get("input_snapshot")), header = object(snapshot.get("publicResult"));
        BudgetInputs.Resolved inputs = frozenInputs(snapshot);
        Map<String, Object> visibleInputs = new LinkedHashMap<>(); SNAPSHOT_FIELDS.forEach(field -> visibleInputs.put(field, canonical(snapshot.get(field))));
        var rows = jdbc.queryForList("SELECT id, status, amount_cents FROM budget_line WHERE tenant_id = 0 AND revision_id = ? AND deleted = FALSE", number(revision, "id"));
        Map<String, Map<String, Object>> amounts = new HashMap<>(); rows.forEach(row -> amounts.put(String.valueOf(number(row, "id")), row));
        List<Line> lines = loadLines(number(revision, "id")).stream().map(frozen -> {
            var line = frozen.input(); var pricing = frozen.pricing(); var amount = amounts.get(line.lineId());
            String priceSource = (String) pricing.getOrDefault("adminPriceSource", pricing.getOrDefault("priceSource", frozen.priceVersionId() == null ? null : "REGIONAL_PRICE"));
            return new Line(line.lineId(), frozen.lineKey(), line.itemId(), line.optionId(), idValue(frozen.priceVersionId()), line.itemCode(), line.publicName(), line.optionLabel(),
                    line.category(), line.source(), (String) pricing.get("selectionGroup"), line.unit(), line.quantity(), line.unitPriceCents(), nullableNumber(amount, "amount_cents"),
                    (String) amount.get("status"), line.freeReason(), line.excludedReason(), frozen.internalNote(), (String) pricing.get("originalQuantity"),
                    nullableNumber(pricing, "originalUnitPriceCents"), "FIXED_ONE".equals(pricing.get("quantitySource")) ? "FIXED_ONE"
                            : (String) pricing.getOrDefault("adminQuantitySource", pricing.get("resolvedQuantitySource")), priceSource, (String) pricing.get("sourceReference"));
        }).toList();
        int selected = ((Number) revision.get("revision_no")).intValue();
        return new Detail(String.valueOf(number(budget, "id")), String.valueOf(number(budget, "project_id")), String.valueOf(number(budget, "user_id")), idValue(budget.get("result_version_id")),
                (String) header.get("projectName"), (String) header.get("schemeName"), (String) snapshot.get("regionCode"), (String) header.get("regionName"), "ITEMIZED_V1",
                Boolean.TRUE.equals(budget.get("saved")), current, String.valueOf(number(revision, "id")), selected, (String) revision.get("completeness"), number(revision, "priced_subtotal_cents"),
                nullableNumber(revision, "total_cents"), instant(revision.get("create_time")), selected != current,
                Map.of("BODY", number(revision, "body_subtotal_cents"), "EXTERIOR", number(revision, "exterior_subtotal_cents")), visibleInputs, inputs.missingFields(), inputs.warnings(), lines);
    }

    private static Summary summary(Detail detail) {
        return new Summary(detail.budgetId(), detail.projectId(), detail.userId(), detail.resultVersionId(), detail.projectName(), detail.schemeName(), detail.regionCode(), detail.regionName(),
                detail.model(), detail.saved(), detail.currentVersion(), detail.revisionId(), detail.revisionNo(), detail.completeness(), detail.pricedSubtotalCents(), detail.totalCents(), detail.createdAt());
    }

    private Map<String, Object> requireBudget(long budgetId, boolean lock) {
        var rows = jdbc.queryForList("SELECT b.* FROM budget_estimate b WHERE b.tenant_id = 0 AND b.id = ? AND b.deleted = FALSE" + VISIBLE_PROJECT + (lock ? " FOR UPDATE OF b" : ""), budgetId);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        Map<String, Object> budget = rows.get(0);
        if (!"ITEMIZED_V1".equals(budget.get("model"))) invalid();
        if (lock) {
            if (jdbc.queryForList("SELECT id FROM design_project WHERE tenant_id = 0 AND id = ? AND user_id = ? AND deleted = FALSE FOR SHARE", number(budget, "project_id"), number(budget, "user_id")).isEmpty()) throw exception(RESOURCE_FORBIDDEN);
            if (budget.get("result_version_id") != null && jdbc.queryForList("SELECT id FROM design_result_version WHERE tenant_id = 0 AND id = ? AND project_id = ? AND deleted = FALSE FOR SHARE", number(budget, "result_version_id"), number(budget, "project_id")).isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        }
        return budget;
    }

    private Map<String, Object> revision(long budgetId, Long id, int current) {
        var rows = jdbc.queryForList("SELECT * FROM budget_revision WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE AND "
                + (id == null ? "revision_no = ?" : "id = ?"), budgetId, id == null ? current : id);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }

    private <T> T command(long actor, String operation, long budgetId, String key, Map<String, Object> body, Class<T> type, Supplier<T> action) {
        if (actor <= 0) throw exception(RESOURCE_FORBIDDEN);
        if (key == null || key.isBlank() || !key.equals(key.trim()) || key.length() > 64) invalid();
        String hash = fingerprint(budgetId, body);
        try {
            return transaction(() -> {
                requireBudget(budgetId, false); // Authorization still applies to replays, before looking at the stored response.
                jdbc.update("INSERT INTO budget_catalog_command (tenant_id, actor_id, operation, idempotency_key, request_hash) VALUES (0, ?, ?, ?, ?) ON CONFLICT DO NOTHING", actor, operation, key, hash);
                var receipt = jdbc.queryForMap("SELECT request_hash, response::text FROM budget_catalog_command WHERE tenant_id = 0 AND actor_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE", actor, operation, key);
                if (!hash.equals(receipt.get("request_hash"))) throw exception(IDEMPOTENCY_KEY_REUSED);
                if (receipt.get("response") != null) {
                    T replay = JsonUtils.parseObject((String) receipt.get("response"), type);
                    // JsonUtils omits null map contents; keep explicit user clears visible on the replay too.
                    if (replay instanceof Detail detail) detail.inputSnapshot().replaceAll((field, value) -> canonical(value));
                    return replay;
                }
                T response = action.get();
                jdbc.update("UPDATE budget_catalog_command SET response = CAST(? AS jsonb) WHERE tenant_id = 0 AND actor_id = ? AND operation = ? AND idempotency_key = ?", JsonUtils.toJsonString(response), actor, operation, key);
                return response;
            });
        } catch (DuplicateKeyException duplicate) { throw exception(BUDGET_INPUT_INVALID); }
    }

    private <T> T transaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try { return tx.execute(status -> work.get()); }
            catch (ConcurrencyFailureException conflict) { if (attempt >= 2) throw exception(STATE_VERSION_CONFLICT); }
        }
    }

    private void audit(long actor, String event, String action, String type, long id, Map<String, Object> detail) {
        audit.record(AuditEventMessage.builder().eventType(event).actorType(AuditEventMessage.ActorType.ADMIN).actorId(String.valueOf(actor))
                .action(action).bizType(type).bizId(String.valueOf(id)).result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L).detail(detail).build());
    }

    private static BudgetInputs.Resolved frozenInputs(Map<String, Object> snapshot) {
        Map<String, Object> values = object(snapshot.get("currentValues"));
        Map<String, String> sources = strings(snapshot.get("currentSources")), quantities = strings(snapshot.get("quantities"));
        var publicResult = object(snapshot.get("publicResult"));
        List<String> warnings = publicResult.get("warnings") instanceof List<?> entries ? entries.stream().map(AdminBudgetRevisionService::string).toList() : List.of();
        return new BudgetInputs.Resolved(values, sources, quantities, INPUT_FIELDS.stream().filter(field -> values.get(field) == null).toList(), warnings);
    }

    private static RevisionRequest parseRequest(Map<String, Object> body) {
        fields(body, "expectedVersion", "reason", "updates", "additions", "removeLineIds");
        int expected = version(body); String reason = text(body, "reason", 500);
        if ((body.containsKey("updates") && body.get("updates") == null) || (body.containsKey("additions") && body.get("additions") == null)) invalid();
        List<Map<String, Object>> updates = objects(body.get("updates")), additions = objects(body.get("additions"));
        List<String> removals = new ArrayList<>();
        if (body.containsKey("removeLineIds")) {
            if (!(body.get("removeLineIds") instanceof List<?> entries) || entries.size() > 100) invalid();
            for (Object value : (List<?>) body.get("removeLineIds")) { String line = string(value); BudgetInputs.positiveId(line); removals.add(line); }
        }
        for (var update : updates) {
            fields(update, "lineId", "quantity", "unitPriceCents", "freeReason", "excludedReason", "internalNote", "optionId");
            id(update, "lineId");
            if (update.size() < 2) invalid();
        }
        for (var addition : additions) {
            String kind = text(addition, "kind", 24);
            if ("PROJECT_CUSTOM".equals(kind)) {
                fields(addition, "kind", "clientKey", "itemCode", "publicName", "category", "unit", "quantity", "unitPriceCents", "freeReason", "excludedReason", "internalNote");
                if (!addition.containsKey("quantity") || !addition.containsKey("unitPriceCents")) invalid();
            } else if ("CATALOG_OPTION".equals(kind)) {
                fields(addition, "kind", "clientKey", "optionId", "quantity", "unitPriceCents", "freeReason", "excludedReason", "internalNote");
                id(addition, "optionId");
            } else invalid();
        }
        if (updates.isEmpty() && additions.isEmpty() && removals.isEmpty()) invalid();
        return new RevisionRequest(expected, reason, updates, additions, List.copyOf(removals));
    }

    private static FrozenLine ownedLine(Map<String, FrozenLine> lines, String id) {
        FrozenLine result = lines.get(id); if (result == null) throw exception(RESOURCE_FORBIDDEN); return result;
    }
    private static void checkVersion(Map<String, Object> budget, int expected) { if (((Number) budget.get("current_revision")).intValue() != expected) throw exception(STATE_VERSION_CONFLICT); }
    private static List<Map<String, Object>> objects(Object value) {
        if (value == null) return List.of();
        if (!(value instanceof List<?> entries) || entries.size() > 100) throw exception(BUDGET_INPUT_INVALID);
        return entries.stream().map(AdminBudgetRevisionService::object).toList();
    }
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw exception(BUDGET_INPUT_INVALID);
        Map<String, Object> result = new LinkedHashMap<>(); map.forEach((key, entry) -> result.put(string(key), entry)); return result;
    }
    private static Map<String, String> strings(Object value) {
        Map<String, String> result = new LinkedHashMap<>(); object(value).forEach((key, entry) -> result.put(key, entry == null ? null : string(entry))); return result;
    }
    private static void fields(Map<String, Object> body, String... allowed) { if (body == null || !Set.of(allowed).containsAll(body.keySet())) invalid(); }
    private static String string(Object value) { if (!(value instanceof String text)) throw exception(BUDGET_INPUT_INVALID); return text; }
    private static String text(Map<String, Object> body, String field, int max) {
        String value = string(body.get(field)); if (value.isBlank() || !value.equals(value.trim()) || value.length() > max) invalid(); return value;
    }
    private static String optionalText(Map<String, Object> body, String field, int max) { return body.get(field) == null ? null : text(body, field, max); }
    private static String id(Map<String, Object> body, String field) { String value = string(body.get(field)); BudgetInputs.positiveId(value); return value; }
    private static String code(Map<String, Object> body, String field, int max, String regex) { String value = text(body, field, max); if (!value.matches(regex)) invalid(); return value; }
    private static int version(Map<String, Object> body) { return (int) integer(body.get("expectedVersion"), 1, Integer.MAX_VALUE - 1); }
    private static Long cents(Object value) { return value == null ? null : integer(value, 0, BudgetCalculator.MAX_UNIT_PRICE_CENTS); }
    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte || value instanceof BigInteger)) invalid();
        BigInteger number = new BigInteger(value.toString());
        if (number.compareTo(BigInteger.valueOf(min)) < 0 || number.compareTo(BigInteger.valueOf(max)) > 0) invalid(); return number.longValueExact();
    }
    private static String quantity(Object value) {
        if (value == null) return null;
        String text = string(value);
        if (text.length() > 20 || !text.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,4})?")) invalid();
        BigDecimal number = new BigDecimal(text);
        if (number.signum() <= 0 || number.compareTo(BudgetInputs.MAX_AREA) > 0) invalid();
        return number.stripTrailingZeros().toPlainString(); // Unit-specific integer/count bounds remain in the calculator.
    }
    private static String decimal(Object value) { return value == null ? null : ((BigDecimal) value).stripTrailingZeros().toPlainString(); }
    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    private static Long nullableNumber(Map<String, Object> row, String field) { return row.get(field) == null ? null : number(row, field); }
    private static Long longId(String value) { return value == null ? null : Long.parseLong(value); }
    private static String idValue(Object value) { return value == null ? null : value.toString(); }
    private static String instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant().toString(); }
    private static Map<String, Object> json(Object value) { return BudgetInputs.readJson(value == null ? null : value.toString()); }
    private static String jsonString(Object value) { return JsonUtils.toJsonString(canonical(value)); }
    private static Object canonical(Object value) {
        if (value == null) return NullNode.getInstance();
        if (value instanceof Map<?, ?> map) { Map<String, Object> sorted = new TreeMap<>(); map.forEach((key, entry) -> sorted.put(string(key), canonical(entry))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(AdminBudgetRevisionService::canonical).toList(); return value;
    }
    private static String fingerprint(long budgetId, Map<String, Object> body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(jsonString(Map.of("budgetId", String.valueOf(budgetId), "body", body)).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void invalid() { throw exception(BUDGET_INPUT_INVALID); }
}
