package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.design.controller.app.vo.AppMyPriceRespVO;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditEventMessage;
import cn.iocoder.yudao.module.infra.zhongshu.audit.AuditPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;

import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_ACCOUNT_PRICE_INVALID;

/**
 * T14: account-scoped unit price overrides on top of the fixed Wuhan baseline catalog.
 * Overrides never write budget_item_price; resets keep their row as REMOVED history, so the
 * estimate-side active set is always the partial-unique ACTIVE rows of one account.
 */
@Service
public class BudgetAccountPriceService {
    private static final long MAX_CENTS = 100_000_000L;
    private static final String PRICE_SELECT = "SELECT i.id AS item_id, i.code AS item_code, i.name AS item_name, i.category, "
            + "o.id AS option_id, o.code AS option_code, o.label, o.selection_group, o.unit, "
            + "p.unit_price_cents AS baseline_cents, a.id AS my_id, a.unit_price_cents AS my_cents, a.reason, a.update_time "
            + "FROM budget_item i "
            + "JOIN budget_option o ON o.tenant_id = i.tenant_id AND o.item_id = i.id AND o.enabled = TRUE AND o.deleted = FALSE "
            + "LEFT JOIN LATERAL (SELECT p.unit_price_cents FROM budget_item_price p WHERE p.tenant_id = 0 AND p.region_id = ? "
            + "AND p.option_id = o.id AND p.status = 'PUBLISHED' AND p.deleted = FALSE AND p.effective_at <= now() "
            + "AND (p.expires_at IS NULL OR p.expires_at > now()) ORDER BY p.id LIMIT 1) p ON TRUE "
            + "LEFT JOIN budget_account_price a ON a.tenant_id = 0 AND a.account_id = ? AND a.option_id = o.id "
            + "AND a.status = 'ACTIVE' AND a.deleted = FALSE ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AuditPort auditPort;

    public BudgetAccountPriceService(DataSource dataSource, PlatformTransactionManager transactionManager, AuditPort auditPort) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(transactionManager);
        this.auditPort = auditPort;
    }

    public record AdminAccountPrice(long accountId, String regionCode, String optionId, String optionLabel, String itemCode,
                                    long unitPriceCents, String reason, String status, String updatedAt) {}

    /** 逐项并列基准价与本人覆盖价；只开放标准目录项，自定义模板项维持后台补价流程。 */
    public AppMyPriceRespVO.Items listPrices(long accountId, String regionCode) {
        if (accountId <= 0) throw exception(RESOURCE_FORBIDDEN);
        var region = region(regionCode);
        var rows = standardRows(accountId, number(region, "id"), null);
        LinkedHashMap<Long, AppMyPriceRespVO.Group> groups = new LinkedHashMap<>();
        for (var row : rows) {
            long itemId = number(row, "item_id");
            groups.computeIfAbsent(itemId, key -> new AppMyPriceRespVO.Group(String.valueOf(key), (String) row.get("item_code"),
                    (String) row.get("item_name"), (String) row.get("category"), new ArrayList<>())).options().add(price(row));
        }
        return new AppMyPriceRespVO.Items((String) region.get("code"), (String) region.get("name"), List.copyOf(groups.values()));
    }

    /** 设置本人覆盖价；upsert 天然幂等，同账号同选项并发由部分唯一索引兜底成单条 ACTIVE。 */
    public AppMyPriceRespVO upsert(long accountId, String regionCode, long optionId, Map<String, Object> body) {
        if (accountId <= 0) throw exception(RESOURCE_FORBIDDEN);
        long regionId = number(region(regionCode), "id");
        if (body == null || !Set.of("unitPriceCents", "reason").containsAll(body.keySet()) || !body.containsKey("unitPriceCents")) {
            throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        }
        Long cents = cents(body.get("unitPriceCents"));
        String reason = body.get("reason") == null ? null : text(body.get("reason"));
        validateStandardOption(optionId);
        var previous = activeOverride(accountId, optionId);
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO budget_account_price (id, account_id, option_id, unit_price_cents, reason, status, tenant_id, creator, updater) "
                    + "VALUES (?, ?, ?, ?, ?, 'ACTIVE', 0, ?, ?) "
                    + "ON CONFLICT (tenant_id, account_id, option_id) WHERE status = 'ACTIVE' AND deleted = FALSE "
                    + "DO UPDATE SET unit_price_cents = EXCLUDED.unit_price_cents, reason = EXCLUDED.reason, "
                    + "version = budget_account_price.version + 1, updater = EXCLUDED.updater, update_time = now()",
                    IdWorker.getId(), accountId, optionId, cents, reason, String.valueOf(accountId), String.valueOf(accountId));
            // 与目录/测算服务一致：审计与写库同事务，改价不留无审计窗口
            var detail = new LinkedHashMap<String, Object>();
            detail.put("beforeCents", previous == null ? null : number(previous, "unit_price_cents"));
            detail.put("afterCents", cents);
            detail.put("reason", reason);
            audit(accountId, previous == null ? "SET" : "UPDATE", optionId, detail);
        });
        return myPrice(accountId, regionId, optionId);
    }

    /** 恢复默认：置 REMOVED 保留历史；无覆盖时幂等成功。 */
    public AppMyPriceRespVO reset(long accountId, String regionCode, long optionId) {
        if (accountId <= 0) throw exception(RESOURCE_FORBIDDEN);
        long regionId = number(region(regionCode), "id");
        var previous = activeOverride(accountId, optionId);
        tx.execute(status -> {
            int removed = jdbc.update("UPDATE budget_account_price SET status = 'REMOVED', version = version + 1, updater = ?, update_time = now() "
                    + "WHERE tenant_id = 0 AND account_id = ? AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                    String.valueOf(accountId), accountId, optionId);
            // 并发窗口：previous 可能在读取后由他人 upsert 写入，仅在有行被移除且有已知原值时审计
            if (removed > 0 && previous != null) {
                audit(accountId, "RESET", optionId, Map.of("beforeCents", number(previous, "unit_price_cents")));
            }
            return removed;
        });
        return myPrice(accountId, regionId, optionId);
    }

    /** 管理端只读查询：某账号的覆盖历史（含已恢复的 REMOVED 行），无任何干预入口。 */
    public PageResult<AdminAccountPrice> pageByAccount(long accountId, String regionCode, int pageNo, int pageSize) {
        if (accountId <= 0 || pageNo < 1 || pageSize < 1 || pageSize > 100) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        String validatedRegionCode = (String) region(regionCode).get("code");
        Long total = jdbc.queryForObject("SELECT count(*) FROM budget_account_price WHERE tenant_id = 0 AND account_id = ? AND deleted = FALSE", Long.class, accountId);
        var rows = jdbc.query("SELECT a.option_id, a.unit_price_cents, a.reason, a.status, a.update_time, o.label, i.code AS item_code "
                + "FROM budget_account_price a "
                + "JOIN budget_option o ON o.tenant_id = a.tenant_id AND o.id = a.option_id AND o.deleted = FALSE "
                + "JOIN budget_item i ON i.tenant_id = o.tenant_id AND i.id = o.item_id AND i.deleted = FALSE "
                + "WHERE a.tenant_id = 0 AND a.account_id = ? AND a.deleted = FALSE ORDER BY a.update_time DESC, a.id DESC LIMIT ? OFFSET ?",
                accountPriceMapper(accountId, validatedRegionCode), accountId, pageSize, (long) (pageNo - 1) * pageSize);
        return new PageResult<>(rows, total);
    }

    private RowMapper<AdminAccountPrice> accountPriceMapper(long accountId, String regionCode) {
        return (rs, index) -> new AdminAccountPrice(accountId, regionCode, rs.getString("option_id"), rs.getString("label"), rs.getString("item_code"),
                rs.getLong("unit_price_cents"), rs.getString("reason"), rs.getString("status"),
                rs.getTimestamp("update_time").toInstant().toString());
    }

    private List<Map<String, Object>> standardRows(long accountId, long regionId, Long optionId) {
        var args = new ArrayList<Object>(List.of(regionId, accountId));
        String filter = "WHERE i.tenant_id = 0 AND i.source = 'STANDARD' AND i.enabled = TRUE AND i.public_selectable = TRUE AND i.deleted = FALSE";
        if (optionId != null) {
            filter += " AND o.id = ?";
            args.add(optionId);
        }
        return jdbc.queryForList(PRICE_SELECT + filter + " ORDER BY i.sort_order, i.id, o.sort_order, o.id", args.toArray());
    }

    private AppMyPriceRespVO myPrice(long accountId, long regionId, long optionId) {
        var rows = standardRows(accountId, regionId, optionId);
        if (rows.isEmpty()) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        return price(rows.get(0));
    }

    private AppMyPriceRespVO price(Map<String, Object> row) {
        Long baseline = row.get("baseline_cents") == null ? null : number(row, "baseline_cents");
        boolean overridden = row.get("my_id") != null;
        Timestamp updatedAt = (Timestamp) row.get("update_time");
        return new AppMyPriceRespVO(String.valueOf(number(row, "option_id")), (String) row.get("option_code"), (String) row.get("label"),
                (String) row.get("selection_group"), (String) row.get("unit"), baseline,
                overridden ? number(row, "my_cents") : null, overridden ? "ACCOUNT_OVERRIDE" : baseline == null ? "MISSING" : "DEFAULT",
                overridden ? (String) row.get("reason") : null, overridden && updatedAt != null ? updatedAt.toInstant().toString() : null);
    }

    private Map<String, Object> activeOverride(long accountId, long optionId) {
        var rows = jdbc.queryForList("SELECT id, version, unit_price_cents FROM budget_account_price "
                + "WHERE tenant_id = 0 AND account_id = ? AND option_id = ? AND status = 'ACTIVE' AND deleted = FALSE", accountId, optionId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void validateStandardOption(long optionId) {
        var rows = jdbc.queryForList("SELECT i.source, i.enabled AS item_enabled, i.public_selectable, o.enabled AS option_enabled "
                + "FROM budget_option o JOIN budget_item i ON i.tenant_id = o.tenant_id AND i.id = o.item_id "
                + "WHERE o.tenant_id = 0 AND o.id = ? AND o.deleted = FALSE", optionId);
        if (rows.isEmpty()) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        var row = rows.get(0);
        if (!"STANDARD".equals(row.get("source")) || !Boolean.TRUE.equals(row.get("item_enabled"))
                || !Boolean.TRUE.equals(row.get("public_selectable")) || !Boolean.TRUE.equals(row.get("option_enabled"))) {
            throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        }
    }

    private void audit(long accountId, String action, long optionId, Map<String, Object> detail) {
        auditPort.record(AuditEventMessage.builder().eventType("BUDGET_ACCOUNT_PRICE_CHANGED").actorType(AuditEventMessage.ActorType.USER)
                .actorId(String.valueOf(accountId)).action(action).bizType("budget_account_price").bizId(String.valueOf(optionId))
                .result(AuditEventMessage.AuditResult.SUCCESS).tenantId(0L).detail(detail).build());
    }

    private Map<String, Object> region(String regionCode) {
        if (regionCode == null || regionCode.length() > 32 || !regionCode.matches("[A-Za-z0-9_-]+")) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        var rows = jdbc.queryForList("SELECT id, code, name FROM budget_region WHERE tenant_id = 0 AND code = ? AND enabled = TRUE AND deleted = FALSE", regionCode);
        if (rows.isEmpty()) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        return rows.get(0);
    }

    private static Long cents(Object value) {
        if (!(value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte)) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        long result = ((Number) value).longValue();
        if (result < 1 || result > MAX_CENTS) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        return result;
    }

    private static String text(Object value) {
        if (!(value instanceof String result) || result.isBlank() || !result.equals(result.trim()) || result.length() > 500) throw exception(BUDGET_ACCOUNT_PRICE_INVALID);
        return result;
    }

    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
