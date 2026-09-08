package cn.iocoder.yudao.module.commerce.pricing;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.PRICE_RULE_CHANGED;

/**
 * 版本化计价规则（架构 §6.5 generation_price_rule）
 *
 * 合同：
 * - 任务创建时用 {@link #quote} 冻结快照；扣点前用 {@link #validateSnapshotStillValid} 校验，
 *   规则被替换/停用/版本变化抛 PRICE_RULE_CHANGED（可重试：重新报价后提交）；
 * - 历史订单/任务永远引用自己的快照，规则更新不影响已冻结的报价；
 * - 同一阶段允许多条规则按生效期接力，解析取最近生效的一条。
 */
@Slf4j
@Service
public class PriceRuleService {

    public record PriceRule(long id, long version, String stage, long unitPointCost,
                            int minCount, int maxCount, Instant effectiveAt, Instant expiresAt, String status) {
    }

    private final JdbcTemplate jdbcTemplate;

    public PriceRuleService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /** 新增一条规则（版本 1，ACTIVE）；调整价格 = 追加新规则，不修改历史 */
    public long createRule(String stage, long unitPointCost, int minCount, int maxCount,
                           Instant effectiveAt, Instant expiresAt) {
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO generation_price_rule (id, stage, unit_point_cost, min_count, max_count, "
                        + "effective_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, stage, unitPointCost, minCount, maxCount,
                Timestamp.from(effectiveAt), expiresAt == null ? null : Timestamp.from(expiresAt));
        return id;
    }

    /** 停用规则（version + 1；已冻结快照据此判定失效） */
    public boolean retireRule(long ruleId) {
        return jdbcTemplate.update(
                "UPDATE generation_price_rule SET status = 'RETIRED', version = version + 1, update_time = now() "
                        + "WHERE id = ? AND status = 'ACTIVE'", ruleId) == 1;
    }

    /** 解析某时点生效的规则（最近生效优先） */
    public Optional<PriceRule> resolve(String stage, Instant at) {
        List<PriceRule> rows = jdbcTemplate.query(
                "SELECT id, version, stage, unit_point_cost, min_count, max_count, effective_at, expires_at, status "
                        + "FROM generation_price_rule "
                        + "WHERE stage = ? AND status = 'ACTIVE' AND effective_at <= ? "
                        + "  AND (expires_at IS NULL OR expires_at > ?) AND deleted = FALSE "
                        + "ORDER BY effective_at DESC, id DESC LIMIT 1",
                (rs, i) -> new PriceRule(rs.getLong("id"), rs.getLong("version"), rs.getString("stage"),
                        rs.getLong("unit_point_cost"), rs.getInt("min_count"), rs.getInt("max_count"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")),
                stage, Timestamp.from(at), Timestamp.from(at));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 生成计价快照；数量必须在规则允许区间内 */
    public PriceRuleQuote quote(String stage, int count, Instant at) {
        PriceRule rule = resolve(stage, at)
                .orElseThrow(() -> new IllegalStateException("阶段无生效计价规则: " + stage));
        if (count < rule.minCount() || count > rule.maxCount()) {
            throw new IllegalStateException(
                    "数量超出规则允许区间: count=" + count + " 允许=" + rule.minCount() + "~" + rule.maxCount());
        }
        return new PriceRuleQuote(rule.id(), rule.version(), stage, rule.unitPointCost(),
                count, rule.unitPointCost() * count, rule.effectiveAt());
    }

    /**
     * 扣点前校验快照仍有效（规则存在、ACTIVE、版本一致、仍在生效期）；
     * 失败抛 PRICE_RULE_CHANGED，调用方可重新报价后重试。
     */
    public void validateSnapshotStillValid(PriceRuleQuote quote, Instant at) {
        List<PriceRule> rows = jdbcTemplate.query(
                "SELECT id, version, status, effective_at, expires_at FROM generation_price_rule WHERE id = ?",
                (rs, i) -> new PriceRule(rs.getLong("id"), rs.getLong("version"), quote.getStage(),
                        0, 0, 0, rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")),
                quote.getRuleId());
        if (rows.isEmpty()) {
            throw exception(PRICE_RULE_CHANGED);
        }
        PriceRule current = rows.get(0);
        boolean stillValid = "ACTIVE".equals(current.status())
                && current.version() == quote.getRuleVersion()
                && !current.effectiveAt().isAfter(at)
                && (current.expiresAt() == null || current.expiresAt().isAfter(at));
        if (!stillValid) {
            throw exception(PRICE_RULE_CHANGED);
        }
    }

}
