package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.util.json.JsonUtils;
import cn.iocoder.yudao.module.design.controller.admin.vo.AdminBudgetQuoteVO.Quote;
import cn.iocoder.yudao.module.design.controller.app.vo.AppBudgetQuoteRespVO;
import cn.iocoder.yudao.module.design.controller.app.vo.AppItemizedBudgetRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.util.*;
import java.util.function.Supplier;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.*;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.*;

/** Explicit final-quote workflow. Budget revisions remain immutable and their calculated totals are never rewritten. */
@Service
public class BudgetQuoteService {
    private static final long MAX_CENTS = 10_000_000_000L;
    private static final String DISCLAIMER = "正式报价以本版本金额及已列范围为准；未列项目、变更及现场签证另行确认。";
    private static final String VISIBLE_PROJECT = " AND EXISTS (SELECT 1 FROM design_project p WHERE p.tenant_id = 0 AND p.id = b.project_id AND p.user_id = b.user_id AND p.deleted = FALSE)"
            + " AND (b.result_version_id IS NULL OR EXISTS (SELECT 1 FROM design_result_version v WHERE v.tenant_id = 0 AND v.id = b.result_version_id AND v.project_id = b.project_id AND v.deleted = FALSE))";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditPort audit;

    public BudgetQuoteService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort audit) {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.audit = audit;
    }

    public List<Quote> adminList(String budgetId) {
        long budget = BudgetInputs.positiveId(budgetId);
        return transaction(() -> {
            Map<String, Object> master = requireBudget(budget, false);
            long currentRevision = currentRevisionId(master);
            Integer latestVersion = jdbc.queryForObject("SELECT coalesce(max(quote_version), 0) FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE", Integer.class, budget);
            Long currentPublic = currentPublicQuoteId(budget);
            return jdbc.queryForList("SELECT id FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE ORDER BY quote_version DESC", budget)
                    .stream().map(row -> quote(requireQuote(number(row, "id"), false), currentRevision,
                            latestVersion == null ? 0 : latestVersion, currentPublic)).toList();
        });
    }

    public Quote adminGet(String quoteId) {
        long id = BudgetInputs.positiveId(quoteId);
        return transaction(() -> {
            Map<String, Object> row = requireQuote(id, false);
            Map<String, Object> budget = requireBudget(number(row, "estimate_id"), false);
            int latest = Optional.ofNullable(jdbc.queryForObject("SELECT coalesce(max(quote_version), 0) FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE", Integer.class, number(row, "estimate_id"))).orElse(0);
            return quote(row, currentRevisionId(budget), latest, currentPublicQuoteId(number(row, "estimate_id")));
        });
    }

    public Quote create(long actor, String budgetId, String key, Map<String, Object> body) {
        long budget = BudgetInputs.positiveId(budgetId);
        fields(body, "revisionId", "expectedVersion", "finalPriceCents", "adjustmentCents", "reason");
        long revisionId = BudgetInputs.positiveId(string(body.get("revisionId")));
        int expected = positiveInt(body.get("expectedVersion"));
        boolean hasFinal = body.containsKey("finalPriceCents"), hasAdjustment = body.containsKey("adjustmentCents");
        if (hasFinal == hasAdjustment) invalid();
        Long requestedFinal = hasFinal ? money(body.get("finalPriceCents"), 0, MAX_CENTS) : null;
        Long requestedAdjustment = hasAdjustment ? money(body.get("adjustmentCents"), -MAX_CENTS, MAX_CENTS) : null;
        String reason = text(body.get("reason"), 500);
        return command(actor, "QUOTE_CREATE", budget, key, body, Quote.class, () -> {
            Map<String, Object> master = requireBudget(budget, true);
            if (((Number) master.get("current_revision")).intValue() != expected) throw exception(STATE_VERSION_CONFLICT);
            Map<String, Object> revision = requireRevision(budget, revisionId);
            if (((Number) revision.get("revision_no")).intValue() != expected || !"COMPLETE".equals(revision.get("completeness")) || revision.get("total_cents") == null)
                throw exception(BUDGET_INCOMPLETE);
            long calculated = number(revision, "total_cents");
            long adjustment;
            long finalPrice;
            try {
                finalPrice = requestedFinal == null ? Math.addExact(calculated, requestedAdjustment) : requestedFinal;
                adjustment = requestedAdjustment == null ? Math.subtractExact(finalPrice, calculated) : requestedAdjustment;
            } catch (ArithmeticException overflow) { throw exception(BUDGET_AMOUNT_LIMIT); }
            if (finalPrice < 0 || finalPrice > MAX_CENTS || adjustment < -MAX_CENTS || adjustment > MAX_CENTS)
                throw exception(BUDGET_AMOUNT_LIMIT);
            int quoteVersion = Optional.ofNullable(jdbc.queryForObject("SELECT coalesce(max(quote_version), 0) + 1 FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE", Integer.class, budget)).orElse(1);
            long id = IdWorker.getId();
            Timestamp now = jdbc.queryForObject("SELECT now()", Timestamp.class);
            jdbc.update("INSERT INTO budget_quote (id, estimate_id, revision_id, completeness, quote_version, calculated_total_cents, adjustment_cents, final_price_cents, reason, status, version, actor_id, idempotency_key, request_hash, tenant_id, creator, updater, create_time, update_time) "
                            + "VALUES (?, ?, ?, 'COMPLETE', ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?, ?, 0, ?, ?, ?, ?)",
                    id, budget, revisionId, quoteVersion, calculated, adjustment, finalPrice, reason, actor, key,
                    fingerprint(budget, body), String.valueOf(actor), String.valueOf(actor), now, now);
            audit(actor, "BUDGET_QUOTE_CHANGED", "CREATE_DRAFT", id, Map.of("budgetId", budgetId, "revisionId", String.valueOf(revisionId), "quoteVersion", quoteVersion));
            return quote(requireQuote(id, false), currentRevisionId(master), quoteVersion, currentPublicQuoteId(budget));
        });
    }

    public Quote publish(long actor, String quoteId, String key, Map<String, Object> body) {
        return transition(actor, quoteId, key, body, true);
    }

    public Quote withdraw(long actor, String quoteId, String key, Map<String, Object> body) {
        return transition(actor, quoteId, key, body, false);
    }

    public List<AppBudgetQuoteRespVO> appList(long userId, String projectId) {
        long project = BudgetInputs.positiveId(projectId);
        return transaction(() -> {
            requireOwnedProject(userId, project);
            var rows = jdbc.queryForList("SELECT q.id FROM budget_quote q JOIN budget_estimate b ON b.id = q.estimate_id AND b.tenant_id = q.tenant_id "
                    + "WHERE q.tenant_id = 0 AND b.project_id = ? AND b.user_id = ? AND b.deleted = FALSE AND q.deleted = FALSE AND q.status IN ('PUBLISHED','WITHDRAWN') ORDER BY q.quote_version DESC, q.id DESC", project, userId);
            return rows.stream().map(row -> {
                Map<String, Object> quote = requireOwnedQuote(userId, project, number(row, "id"));
                return publicQuote(quote, Objects.equals(latestFinalizedQuoteId(number(quote, "estimate_id")), number(row, "id")));
            }).toList();
        });
    }

    public AppBudgetQuoteRespVO appGet(long userId, String projectId, String quoteId) {
        long project = BudgetInputs.positiveId(projectId), id = BudgetInputs.positiveId(quoteId);
        return transaction(() -> {
            Map<String, Object> row = requireOwnedQuote(userId, project, id);
            return publicQuote(row, Objects.equals(latestFinalizedQuoteId(number(row, "estimate_id")), id));
        });
    }

    private Quote transition(long actor, String quoteId, String key, Map<String, Object> body, boolean publish) {
        long id = BudgetInputs.positiveId(quoteId);
        fields(body, "expectedVersion", "reason");
        int expected = positiveInt(body.get("expectedVersion"));
        String reason = text(body.get("reason"), 500);
        Map<String, Object> observed = requireQuote(id, false);
        long budget = number(observed, "estimate_id");
        String operation = publish ? "QUOTE_PUBLISH" : "QUOTE_WITHDRAW";
        return command(actor, operation, budget, key, Map.of("quoteId", quoteId, "body", body), Quote.class, () -> {
            Map<String, Object> master = requireBudget(budget, true);
            Map<String, Object> row = requireQuote(id, true);
            if (((Number) row.get("version")).intValue() != expected) throw exception(STATE_VERSION_CONFLICT);
            Timestamp now = jdbc.queryForObject("SELECT now()", Timestamp.class);
            if (publish) {
                if (!"DRAFT".equals(row.get("status"))) throw exception(STATE_VERSION_CONFLICT);
                int latest = Optional.ofNullable(jdbc.queryForObject("SELECT coalesce(max(quote_version), 0) FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE", Integer.class, budget)).orElse(0);
                if (number(row, "revision_id") != currentRevisionId(master) || ((Number) row.get("quote_version")).intValue() != latest)
                    throw exception(STATE_VERSION_CONFLICT);
                Map<String, Object> revision = requireRevision(budget, number(row, "revision_id"));
                if (!"COMPLETE".equals(revision.get("completeness")) || revision.get("total_cents") == null || number(revision, "total_cents") != number(row, "calculated_total_cents"))
                    throw exception(BUDGET_INCOMPLETE);
                jdbc.update("UPDATE budget_quote SET status = 'PUBLISHED', version = version + 1, published_by = ?, published_at = ?, updater = ?, update_time = ? WHERE tenant_id = 0 AND id = ?",
                        actor, now, String.valueOf(actor), now, id);
            } else {
                if (!"PUBLISHED".equals(row.get("status")) || !Objects.equals(currentPublicQuoteId(budget), id))
                    throw exception(STATE_VERSION_CONFLICT);
                jdbc.update("UPDATE budget_quote SET status = 'WITHDRAWN', version = version + 1, withdrawn_by = ?, withdrawn_at = ?, withdrawal_reason = ?, updater = ?, update_time = ? WHERE tenant_id = 0 AND id = ?",
                        actor, now, reason, String.valueOf(actor), now, id);
            }
            audit(actor, "BUDGET_QUOTE_CHANGED", publish ? "PUBLISH" : "WITHDRAW", id,
                    Map.of("budgetId", String.valueOf(budget), "reason", reason));
            Map<String, Object> changed = requireQuote(id, false);
            int latest = Optional.ofNullable(jdbc.queryForObject("SELECT coalesce(max(quote_version), 0) FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE", Integer.class, budget)).orElse(0);
            return quote(changed, currentRevisionId(master), latest, currentPublicQuoteId(budget));
        });
    }

    private Quote quote(Map<String, Object> row, long currentRevision, int latestVersion, Long currentPublic) {
        boolean stale = "DRAFT".equals(row.get("status")) && (number(row, "revision_id") != currentRevision || ((Number) row.get("quote_version")).intValue() != latestVersion);
        boolean current = "PUBLISHED".equals(row.get("status")) && Objects.equals(currentPublic, number(row, "id"));
        return new Quote(String.valueOf(number(row, "id")), String.valueOf(number(row, "estimate_id")), String.valueOf(number(row, "revision_id")),
                ((Number) row.get("revision_no")).intValue(), ((Number) row.get("quote_version")).intValue(), (String) row.get("status"), ((Number) row.get("version")).intValue(),
                number(row, "calculated_total_cents"), number(row, "adjustment_cents"), number(row, "final_price_cents"), (String) row.get("reason"), String.valueOf(number(row, "actor_id")),
                stale, current, idValue(row.get("published_by")), instant(row.get("published_at")), idValue(row.get("withdrawn_by")), instant(row.get("withdrawn_at")),
                (String) row.get("withdrawal_reason"), publicQuote(row, current));
    }

    private AppBudgetQuoteRespVO publicQuote(Map<String, Object> row, boolean current) {
        Map<String, Object> snapshot = BudgetInputs.readJson(String.valueOf(row.get("input_snapshot")));
        Map<String, Object> header = map(snapshot.get("publicResult"));
        var groups = new LinkedHashMap<String, MutableItem>();
        for (Map<String, Object> line : jdbc.queryForList("SELECT * FROM budget_line WHERE tenant_id = 0 AND revision_id = ? AND deleted = FALSE ORDER BY sort_order, id", number(row, "revision_id"))) {
            String key = line.get("item_id") == null ? "LINE:" + line.get("line_key") : "ITEM:" + line.get("item_id");
            MutableItem item = groups.computeIfAbsent(key, ignored -> new MutableItem(idValue(line.get("item_id")), (String) line.get("item_code"),
                    (String) line.get("category"), (String) line.get("public_name"), (String) line.get("source")));
            String status = (String) line.get("status");
            Long amount = nullableNumber(line, "amount_cents");
            if (amount != null) item.priced += amount;
            if (!Set.of("PRICED", "EXCLUDED").contains(status)) item.complete = false;
            Object quantity = line.get("quantity");
            item.lines.add(new AppItemizedBudgetRespVO.Line(String.valueOf(number(line, "id")), (String) line.get("option_label"), status, amount,
                    quantity == null ? null : new java.math.BigDecimal(quantity.toString()).stripTrailingZeros().toPlainString(),
                    (String) line.get("unit")));
        }
        List<AppItemizedBudgetRespVO.Item> items = groups.values().stream().map(item -> new AppItemizedBudgetRespVO.Item(item.itemId, item.code, item.category,
                item.name, item.source, item.complete ? "COMPLETE" : "INCOMPLETE", item.priced, item.complete ? item.priced : null, List.copyOf(item.lines))).toList();
        return new AppBudgetQuoteRespVO(String.valueOf(number(row, "id")), ((Number) row.get("quote_version")).intValue(), String.valueOf(number(row, "estimate_id")),
                String.valueOf(number(row, "revision_id")), String.valueOf(number(row, "project_id")), stringValue(header.get("projectName")), idValue(row.get("result_version_id")),
                stringValue(header.get("schemeName")), stringValue(header.get("regionName")), (String) row.get("status"), current,
                number(row, "calculated_total_cents"), number(row, "adjustment_cents"), number(row, "final_price_cents"),
                Map.of("BODY", number(row, "body_subtotal_cents"), "EXTERIOR", number(row, "exterior_subtotal_cents")), items,
                instant(row.get("published_at")), instant(row.get("withdrawn_at")), DISCLAIMER);
    }

    private Map<String, Object> requireBudget(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT b.* FROM budget_estimate b WHERE b.tenant_id = 0 AND b.id = ? AND b.model = 'ITEMIZED_V1' AND b.deleted = FALSE" + VISIBLE_PROJECT + (lock ? " FOR UPDATE OF b" : ""), id);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }

    private Map<String, Object> requireRevision(long budget, long revision) {
        var rows = jdbc.queryForList("SELECT * FROM budget_revision WHERE tenant_id = 0 AND estimate_id = ? AND id = ? AND deleted = FALSE", budget, revision);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }

    private Map<String, Object> requireQuote(long id, boolean lock) {
        var rows = jdbc.queryForList("SELECT q.*, b.project_id, b.user_id, b.result_version_id, r.revision_no, r.input_snapshot, r.body_subtotal_cents, r.exterior_subtotal_cents "
                + "FROM budget_quote q JOIN budget_estimate b ON b.tenant_id = q.tenant_id AND b.id = q.estimate_id "
                + "JOIN budget_revision r ON r.tenant_id = q.tenant_id AND r.id = q.revision_id "
                + "WHERE q.tenant_id = 0 AND q.id = ? AND q.deleted = FALSE AND b.deleted = FALSE AND r.deleted = FALSE" + VISIBLE_PROJECT + (lock ? " FOR UPDATE OF q" : ""), id);
        if (rows.isEmpty()) throw exception(RESOURCE_FORBIDDEN);
        return rows.get(0);
    }

    private Map<String, Object> requireOwnedQuote(long user, long project, long id) {
        Map<String, Object> row = requireQuote(id, false);
        if (number(row, "user_id") != user || number(row, "project_id") != project || "DRAFT".equals(row.get("status")))
            throw exception(RESOURCE_FORBIDDEN);
        return row;
    }

    private void requireOwnedProject(long user, long project) {
        if (jdbc.queryForList("SELECT id FROM design_project WHERE tenant_id = 0 AND id = ? AND user_id = ? AND deleted = FALSE", project, user).isEmpty())
            throw exception(RESOURCE_FORBIDDEN);
    }

    private long currentRevisionId(Map<String, Object> budget) {
        return jdbc.queryForObject("SELECT id FROM budget_revision WHERE tenant_id = 0 AND estimate_id = ? AND revision_no = ? AND deleted = FALSE", Long.class,
                number(budget, "id"), ((Number) budget.get("current_revision")).intValue());
    }

    /** Latest finalized row controls visibility; a withdrawal never falls back to an older published amount. */
    private Long currentPublicQuoteId(long budget) {
        Long latest = latestFinalizedQuoteId(budget);
        if (latest == null) return null;
        Map<String, Object> row = requireQuote(latest, false);
        return "PUBLISHED".equals(row.get("status")) ? latest : null;
    }

    private Long latestFinalizedQuoteId(long budget) {
        var rows = jdbc.queryForList("SELECT id, status FROM budget_quote WHERE tenant_id = 0 AND estimate_id = ? AND deleted = FALSE AND status IN ('PUBLISHED','WITHDRAWN') ORDER BY quote_version DESC, id DESC LIMIT 1", budget);
        return rows.isEmpty() ? null : number(rows.get(0), "id");
    }

    private <T> T command(long actor, String operation, long budget, String key, Object body, Class<T> type, Supplier<T> action) {
        if (actor <= 0) throw exception(RESOURCE_FORBIDDEN);
        if (key == null || key.isBlank() || !key.equals(key.trim()) || key.length() > 64) invalid();
        String hash = fingerprint(budget, body);
        try {
            return transaction(() -> {
                requireBudget(budget, false);
                jdbc.update("INSERT INTO budget_catalog_command (tenant_id, actor_id, operation, idempotency_key, request_hash) VALUES (0, ?, ?, ?, ?) ON CONFLICT DO NOTHING", actor, operation, key, hash);
                Map<String, Object> receipt = jdbc.queryForMap("SELECT request_hash, response::text FROM budget_catalog_command WHERE tenant_id = 0 AND actor_id = ? AND operation = ? AND idempotency_key = ? FOR UPDATE", actor, operation, key);
                if (!hash.equals(receipt.get("request_hash"))) throw exception(IDEMPOTENCY_KEY_REUSED);
                if (receipt.get("response") != null) return JsonUtils.parseObject((String) receipt.get("response"), type);
                T response = action.get();
                jdbc.update("UPDATE budget_catalog_command SET response = CAST(? AS jsonb) WHERE tenant_id = 0 AND actor_id = ? AND operation = ? AND idempotency_key = ?", JsonUtils.toJsonString(response), actor, operation, key);
                return response;
            });
        } catch (DuplicateKeyException duplicate) { throw exception(STATE_VERSION_CONFLICT); }
    }

    private <T> T transaction(Supplier<T> work) {
        for (int attempt = 0; ; attempt++) {
            try { return tx.execute(status -> work.get()); }
            catch (ConcurrencyFailureException conflict) { if (attempt >= 2) throw exception(STATE_VERSION_CONFLICT); }
        }
    }

    private void audit(long actor, String action, String operation, long id, Map<String, Object> detail) {
        audit.record(AuditEventMessage.builder().eventType(action).actorType(AuditEventMessage.ActorType.ADMIN).actorId(String.valueOf(actor))
                .action(operation).bizType("budget_quote").bizId(String.valueOf(id)).result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L).detail(detail).build());
    }

    private static final class MutableItem {
        final String itemId, code, category, name, source;
        final List<AppItemizedBudgetRespVO.Line> lines = new ArrayList<>();
        long priced;
        boolean complete = true;
        MutableItem(String itemId, String code, String category, String name, String source) {
            this.itemId = itemId; this.code = code; this.category = category; this.name = name; this.source = source;
        }
    }

    private static void fields(Map<String, Object> body, String... allowed) {
        if (body == null || !Set.of(allowed).containsAll(body.keySet())) invalid();
    }
    private static int positiveInt(Object value) { return (int) money(value, 1, Integer.MAX_VALUE - 1); }
    private static long money(Object value, long min, long max) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte || value instanceof BigInteger)) invalid();
        BigInteger parsed = new BigInteger(value.toString());
        if (parsed.compareTo(BigInteger.valueOf(min)) < 0 || parsed.compareTo(BigInteger.valueOf(max)) > 0) invalid();
        return parsed.longValueExact();
    }
    private static String text(Object value, int max) { String result = string(value); if (result.isBlank() || !result.equals(result.trim()) || result.length() > max) invalid(); return result; }
    private static String string(Object value) { if (!(value instanceof String result)) invalid(); return (String) value; }
    private static String stringValue(Object value) { return value instanceof String result ? result : null; }
    private static Map<String, Object> map(Object value) { if (!(value instanceof Map<?, ?> source)) return Map.of(); Map<String, Object> result = new LinkedHashMap<>(); source.forEach((k, v) -> result.put(String.valueOf(k), v)); return result; }
    private static long number(Map<String, Object> row, String field) { return ((Number) row.get(field)).longValue(); }
    private static Long nullableNumber(Map<String, Object> row, String field) { return row.get(field) == null ? null : number(row, field); }
    private static String idValue(Object value) { return value == null ? null : String.valueOf(value); }
    private static String instant(Object value) { return value == null ? null : ((Timestamp) value).toInstant().toString(); }
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> source) { Map<String, Object> sorted = new TreeMap<>(); source.forEach((k, v) -> sorted.put(String.valueOf(k), canonical(v))); return sorted; }
        if (value instanceof List<?> list) return list.stream().map(BudgetQuoteService::canonical).toList();
        return value;
    }
    private static String fingerprint(long budget, Object body) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JsonUtils.toJsonString(Map.of("budgetId", String.valueOf(budget), "body", canonical(body))).getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static void invalid() { throw exception(BUDGET_INPUT_INVALID); }
}
