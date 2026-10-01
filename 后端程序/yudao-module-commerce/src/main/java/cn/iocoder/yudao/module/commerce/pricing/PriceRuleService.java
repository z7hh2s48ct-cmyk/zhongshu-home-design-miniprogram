package cn.iocoder.yudao.module.commerce.pricing;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import cn.iocoder.yudao.module.infra.zhongshu.api.GenerationImageOptions;

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

    public record PriceRule(long id, long version, String stage, String resolution, long unitPointCost,
                            int minCount, int maxCount, Instant effectiveAt, Instant expiresAt, String status) {
    }

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public PriceRuleService(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    PriceRuleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 新增一条规则（版本 1，ACTIVE）；调整价格 = 追加新规则，不修改历史 */
    public long createRule(String stage, long unitPointCost, int minCount, int maxCount,
                           Instant effectiveAt, Instant expiresAt) {
        return createRule(stage, "2K", unitPointCost, minCount, maxCount, effectiveAt, expiresAt);
    }

    public long createRule(String stage, String resolution, long unitPointCost, int minCount, int maxCount,
                           Instant effectiveAt, Instant expiresAt) {
        resolution = GenerationImageOptions.normalize(stage, resolution, null).resolution();
        stage = stage.trim().toUpperCase(java.util.Locale.ROOT);
        if (unitPointCost < 1 || unitPointCost > 1_000_000_000L
                || minCount < 1 || maxCount < minCount || maxCount > 4
                || effectiveAt == null || expiresAt != null && !expiresAt.isAfter(effectiveAt)) {
            throw new IllegalArgumentException("计价规则参数无效");
        }
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO generation_price_rule (id, stage, resolution, unit_point_cost, min_count, max_count, "
                        + "effective_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, stage, resolution, unitPointCost, minCount, maxCount,
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
        return resolve(stage, "2K", at);
    }

    public Optional<PriceRule> resolve(String stage, String resolution, Instant at) {
        List<PriceRule> rows = jdbcTemplate.query(
                "SELECT id, version, stage, resolution, unit_point_cost, min_count, max_count, effective_at, expires_at, status "
                        + "FROM generation_price_rule "
                        + "WHERE stage = ? AND resolution = ? AND status = 'ACTIVE' AND effective_at <= ? "
                        + "  AND (expires_at IS NULL OR expires_at > ?) AND deleted = FALSE "
                        + "ORDER BY effective_at DESC, id DESC LIMIT 1",
                (rs, i) -> new PriceRule(rs.getLong("id"), rs.getLong("version"), rs.getString("stage"),
                        rs.getString("resolution"),
                        rs.getLong("unit_point_cost"), rs.getInt("min_count"), rs.getInt("max_count"),
                        rs.getTimestamp("effective_at").toInstant(),
                        rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toInstant(),
                        rs.getString("status")),
                stage, resolution, Timestamp.from(at), Timestamp.from(at));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** 生成计价快照；数量必须在规则允许区间内 */
    public PriceRuleQuote quote(String stage, int count, Instant at) {
        return quote(stage, "2K", count, at);
    }

    public PriceRuleQuote quote(String stage, String resolution, int count, Instant at) {
        PriceRule rule = resolve(stage, resolution, at)
                .orElseThrow(() -> new IllegalStateException("当前分辨率暂无可用计价规则: " + stage + "/" + resolution));
        if (count < rule.minCount() || count > rule.maxCount()) {
            throw new IllegalStateException(
                    "数量超出规则允许区间: count=" + count + " 允许=" + rule.minCount() + "~" + rule.maxCount());
        }
        return new PriceRuleQuote(rule.id(), rule.version(), stage, resolution, rule.unitPointCost(),
                count, rule.unitPointCost() * count, rule.effectiveAt());
    }

    /**
     * 扣点前校验快照仍有效（规则存在、ACTIVE、版本一致、仍在生效期）；
     * 失败抛 PRICE_RULE_CHANGED，调用方可重新报价后重试。
     */
    public void validateSnapshotStillValid(PriceRuleQuote quote, Instant at) {
        PriceRule current = resolve(quote.getStage(), quote.getResolution(), at)
                .orElseThrow(() -> exception(PRICE_RULE_CHANGED));
        if (current.id() != quote.getRuleId() || current.version() != quote.getRuleVersion()
                || !current.stage().equals(quote.getStage())
                || !current.resolution().equals(quote.getResolution())) {
            throw exception(PRICE_RULE_CHANGED);
        }
    }

}
