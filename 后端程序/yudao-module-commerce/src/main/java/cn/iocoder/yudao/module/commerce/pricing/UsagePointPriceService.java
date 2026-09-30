package cn.iocoder.yudao.module.commerce.pricing;

import cn.iocoder.yudao.module.infra.zhongshu.api.PointLedgerPort;
import cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 预算测算与提示词调用的版本化计价、待扣快照和幂等账本扣点。 */
@Slf4j
@Service
public class UsagePointPriceService implements UsagePricingPort {
    public record PriceRule(long id, String product, long pointCost, long version,
                            Instant effectiveAt, Instant expiresAt, String status) { }
    public record PricePage(List<PriceRule> list, long total) { }

    private final JdbcTemplate jdbc;
    private final PointLedgerPort ledger;
    private final TransactionTemplate tx;

    public UsagePointPriceService(DataSource dataSource, PlatformTransactionManager transactionManager,
                                  PointLedgerPort ledger) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.ledger = ledger;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Override
    public Snapshot quote(String product) {
        requireProduct(product);
        List<PriceRule> rules;
        try {
            rules = jdbc.query("SELECT id, product, point_cost, version, effective_at, expires_at, status "
                        + "FROM service_usage_price_rule WHERE product = ? AND status = 'ACTIVE' AND deleted = FALSE "
                        + "AND effective_at <= now() AND (expires_at IS NULL OR expires_at > now()) "
                        + "ORDER BY effective_at DESC, id DESC LIMIT 1",
                (rs, i) -> new PriceRule(rs.getLong("id"), rs.getString("product"), rs.getLong("point_cost"),
                        rs.getLong("version"), rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")), product);
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[quote][业务积分价格查询失败 product={}]", product, error);
            throw error;
        }
        if (rules.isEmpty()) throw new IllegalStateException("当前业务暂无有效积分价格: " + product);
        PriceRule rule = rules.get(0);
        return new Snapshot(product, rule.id(), rule.version(), rule.pointCost());
    }

    public long createRule(String product, long pointCost, Instant effectiveAt, Instant expiresAt, String operator) {
        requireProduct(product);
        if (pointCost < 1 || pointCost > 1_000_000_000L || effectiveAt == null
                || expiresAt != null && !expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("业务积分价格参数无效");
        }
        long id = IdWorker.getId();
        try {
            jdbc.update("INSERT INTO service_usage_price_rule (id, product, point_cost, effective_at, expires_at, creator, updater) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?)", id, product, pointCost,
                    Timestamp.from(effectiveAt), expiresAt == null ? null : Timestamp.from(expiresAt), operator, operator);
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[createRule][业务积分价格写入失败 product={} rule={} operator={}]", product, id, operator, error);
            throw error;
        }
        return id;
    }

    public PricePage listRules(String product, String status, int pageNo, int pageSize) {
        if (product != null && !product.isBlank()) requireProduct(product);
        if (status != null && !status.isBlank() && !List.of("ACTIVE", "RETIRED").contains(status)) {
            throw new IllegalArgumentException("价格状态无效");
        }
        List<String> filters = new ArrayList<>(List.of("deleted = FALSE"));
        List<Object> params = new ArrayList<>();
        if (product != null && !product.isBlank()) {
            filters.add("product = ?");
            params.add(product);
        }
        if (status != null && !status.isBlank()) {
            filters.add("status = ?");
            params.add(status);
        }
        String where = " WHERE " + String.join(" AND ", filters);
        try {
            Long total = jdbc.queryForObject("SELECT count(*) FROM service_usage_price_rule" + where,
                    Long.class, params.toArray());
            List<Object> pageParams = new ArrayList<>(params);
            pageParams.add(pageSize);
            pageParams.add((long) Math.max(0, pageNo - 1) * pageSize);
            List<PriceRule> list = jdbc.query("SELECT id, product, point_cost, version, effective_at, expires_at, status "
                            + "FROM service_usage_price_rule" + where + " ORDER BY id DESC LIMIT ? OFFSET ?",
                    (rs, i) -> new PriceRule(rs.getLong("id"), rs.getString("product"), rs.getLong("point_cost"),
                            rs.getLong("version"), rs.getTimestamp("effective_at").toInstant(),
                            rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                            rs.getString("status")), pageParams.toArray());
            return new PricePage(list, total == null ? 0 : total);
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[listRules][业务积分价格列表查询失败 product={} status={} page={} size={}]", product, status, pageNo, pageSize, error);
            throw error;
        }
    }

    public boolean retireRule(long ruleId, String operator) {
        try {
            return jdbc.update("UPDATE service_usage_price_rule SET status = 'RETIRED', version = version + 1, "
                            + "updater = ?, update_time = now() WHERE id = ? AND status = 'ACTIVE' AND deleted = FALSE",
                    operator, ruleId) == 1;
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[retireRule][业务积分价格停用失败 rule={} operator={}]", ruleId, operator, error);
            throw error;
        }
    }

    @Override
    public void prepareCharge(long userId, Snapshot snapshot, String bizType, String bizId) {
        if (userId <= 0 || snapshot == null || bizType == null || bizType.isBlank() || bizId == null || bizId.isBlank()) {
            throw new IllegalArgumentException("业务扣点快照无效");
        }
        Snapshot current = quote(snapshot.product());
        if (current.ruleId() != snapshot.ruleId() || current.ruleVersion() != snapshot.ruleVersion()) {
            throw new IllegalStateException("业务积分价格已变更，请重新确认");
        }
        try {
            jdbc.update("INSERT INTO service_usage_charge (id, product, user_id, biz_type, biz_id, price_rule_id, "
                            + "price_rule_version, point_cost, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    IdWorker.getId(), snapshot.product(), userId, bizType, bizId, snapshot.ruleId(),
                    snapshot.ruleVersion(), snapshot.pointCost());
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[prepareCharge][待扣记录创建失败 product={} type={} id={} user={} rule={}]", snapshot.product(), bizType, bizId, userId, snapshot.ruleId(), error);
            throw error;
        }
    }

    @Override
    public boolean chargePrepared(long userId, String product, String bizType, String bizId) {
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                List<Map<String, Object>> rows = jdbc.queryForList("SELECT point_cost, state FROM service_usage_charge "
                            + "WHERE product = ? AND biz_type = ? AND biz_id = ? AND user_id = ? AND deleted = FALSE FOR UPDATE",
                    product, bizType, bizId, userId);
                if (rows.isEmpty()) throw new IllegalStateException("未找到已确认的业务扣点快照: " + product + "/" + bizId);
                Map<String, Object> row = rows.get(0);
                if ("CHARGED".equals(row.get("state"))) return false;
                String ledgerType = switch (product) {
                    case "BUDGET_ESTIMATE" -> "BUDGET_ESTIMATE_DEBIT";
                    case "AI_PROMPT" -> "AI_PROMPT_DEBIT";
                    default -> throw new IllegalArgumentException("业务类型无效: " + product);
                };
                long amount = ((Number) row.get("point_cost")).longValue();
                String key = "USAGE:" + product + ":" + bizType + ":" + bizId;
                long ledgerId = ledger.debit(userId, ledgerType, amount, bizType, bizId, key, null, null);
                jdbc.update("UPDATE service_usage_charge SET ledger_id = ?, state = 'CHARGED', update_time = now() "
                        + "WHERE product = ? AND biz_type = ? AND biz_id = ?", ledgerId, product, bizType, bizId);
                log.info("[chargePrepared][product={} bizType={} bizId={} user={} points={} 已扣点]",
                        product, bizType, bizId, userId, amount);
                return true;
            }));
        } catch (RuntimeException error) {
            log.error("[chargePrepared][业务扣点事务失败 product={} type={} id={} user={}]", product, bizType, bizId, userId, error);
            throw error;
        }
    }

    private static void requireProduct(String product) {
        if (!List.of("BUDGET_ESTIMATE", "AI_PROMPT").contains(product)) {
            throw new IllegalArgumentException("业务价格类型无效");
        }
    }
}
