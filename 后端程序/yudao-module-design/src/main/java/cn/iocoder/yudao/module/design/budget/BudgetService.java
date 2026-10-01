package cn.iocoder.yudao.module.design.budget;

import cn.iocoder.yudao.framework.common.exception.ServiceException;
import cn.iocoder.yudao.module.design.project.DesignProjectService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.exception.ZhongshuErrorCodeConstants.RESOURCE_FORBIDDEN;
import static cn.iocoder.yudao.module.design.enums.ErrorCodeConstants.BUDGET_INPUT_INVALID;

/**
 * 预算测算（架构 §6.8；P0 仅参考区间）：规则版本化，输入快照可复算，历史不漂移
 */
@Service
public class BudgetService {

    public record Estimate(long estimateId, long projectId, String ruleVersion, long totalLowCents,
                           long totalHighCents, Map<String, Object> inputSnapshot,
                           String disclaimer, Instant createdAt) {
    }

    private static final String DISCLAIMER = "仅供参考，不构成报价或结算依据";

    private final JdbcTemplate jdbcTemplate;
    private final DesignProjectService projectService;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;
    private final cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricing;

    @org.springframework.beans.factory.annotation.Autowired
    public BudgetService(DataSource dataSource, DesignProjectService projectService,
                         org.springframework.transaction.PlatformTransactionManager transactionManager,
                         cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort usagePricing) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.projectService = projectService;
        this.transactionTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.usagePricing = usagePricing;
    }

    public BudgetService(DataSource dataSource, DesignProjectService projectService) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.projectService = projectService;
        this.transactionTemplate = null;
        this.usagePricing = null;
    }

    public long createRuleVersion(String regionCode, String structureType, String materialGrade,
                                  long lowCentsPerSqm, long highCentsPerSqm, Instant effectiveAt) {
        long id = IdWorker.getId();
        jdbcTemplate.update(
                "INSERT INTO budget_rule_version (id, region_code, structure_type, material_grade, "
                        + "low_cents_per_sqm, high_cents_per_sqm, effective_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, regionCode, structureType, materialGrade, lowCentsPerSqm, highCentsPerSqm,
                Timestamp.from(effectiveAt));
        return id;
    }

    /** 创建测算：冻结规则版本与输入快照；结果 = 单方区间 × 面积（快照内可复算）；关联结果版本 */
    public Estimate createEstimate(long userId, long projectId, String regionCode,
                                   String structureType, String materialGrade, int buildingArea,
                                   Long resultVersionId) {
        return createEstimate(userId, projectId, regionCode, structureType, materialGrade, buildingArea, resultVersionId, null);
    }

    public Estimate createEstimate(long userId, long projectId, String regionCode,
                                   String structureType, String materialGrade, int buildingArea,
                                   Long resultVersionId,
                                    cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation confirmation) {
        if (usagePricing != null) throw new ServiceException(400, "付费预算测算必须提供 Idempotency-Key");
        if (transactionTemplate != null) return transactionTemplate.execute(status -> createEstimateInside(
                userId, projectId, regionCode, structureType, materialGrade, buildingArea, resultVersionId, confirmation));
        return createEstimateInside(userId, projectId, regionCode, structureType, materialGrade, buildingArea, resultVersionId, confirmation);
    }

    public Estimate createEstimate(long userId, long projectId, String regionCode,
                                   String structureType, String materialGrade, int buildingArea,
                                   Long resultVersionId,
                                   cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation confirmation,
                                   String key) {
        if (key == null || key.isBlank() || key.length() > 128) throw new ServiceException(400, "请提供有效 Idempotency-Key");
        if (transactionTemplate == null) throw new IllegalStateException("BUDGET_TRANSACTION_NOT_WIRED");
        return transactionTemplate.execute(status -> {
            projectService.getProject(projectId, userId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
            var input = new java.util.LinkedHashMap<String,Object>();
            input.put("projectId",String.valueOf(projectId));input.put("region",regionCode);
            input.put("structure",structureType);input.put("material",materialGrade);
            input.put("area",buildingArea);input.put("resultVersionId",resultVersionId);
            String hash;
            try { hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(input))); }
            catch (Exception error) { throw new IllegalArgumentException("Invalid budget input",error); }
            jdbcTemplate.update("INSERT INTO budget_legacy_request(user_id,request_key,request_hash) VALUES(?,?,?) ON CONFLICT DO NOTHING",userId,key,hash);
            var receipt=jdbcTemplate.queryForMap("SELECT request_hash,estimate_id FROM budget_legacy_request WHERE user_id=? AND request_key=? FOR UPDATE",userId,key);
            if (!hash.equals(receipt.get("request_hash"))) throw new ServiceException(409,"同一请求号不能用于不同预算输入");
            if (receipt.get("estimate_id") != null) return getEstimate(userId,((Number)receipt.get("estimate_id")).longValue()).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
            var estimate=createEstimateInside(userId,projectId,regionCode,structureType,materialGrade,buildingArea,resultVersionId,confirmation);
            jdbcTemplate.update("UPDATE budget_legacy_request SET estimate_id=? WHERE user_id=? AND request_key=?",estimate.estimateId(),userId,key);
            return estimate;
        });
    }

    private Estimate createEstimateInside(long userId, long projectId, String regionCode,
                                   String structureType, String materialGrade, int buildingArea,
                                   Long resultVersionId,
                                   cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.Confirmation confirmation) {
        // Reuse the project domain's ownership check before looking up prices or writing a budget.
        projectService.getProject(projectId, userId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        if (resultVersionId != null && !Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM design_result_version WHERE id = ? AND project_id = ? AND deleted = FALSE)",
                Boolean.class, resultVersionId, projectId))) {
            throw exception(RESOURCE_FORBIDDEN);
        }
        if (buildingArea < 1 || buildingArea > 10000) {
            throw exception(BUDGET_INPUT_INVALID);
        }
        List<Map<String, Object>> rules = jdbcTemplate.queryForList(
                "SELECT id, low_cents_per_sqm, high_cents_per_sqm FROM budget_rule_version "
                        + "WHERE region_code = ? AND structure_type = ? AND material_grade = ? "
                        + "AND effective_at <= now() "
                        + "AND (expires_at IS NULL OR expires_at > now()) AND deleted = FALSE "
                        + "ORDER BY effective_at DESC LIMIT 1",
                regionCode, structureType, materialGrade);
        if (rules.isEmpty()) {
            throw new ServiceException(1_071_000_002, "该地区/结构/材料等级暂无生效预算规则");
        }
        long ruleId = ((Number) rules.get(0).get("id")).longValue();
        long low = ((Number) rules.get(0).get("low_cents_per_sqm")).longValue() * buildingArea;
        long high = ((Number) rules.get(0).get("high_cents_per_sqm")).longValue() * buildingArea;
        long id = IdWorker.getId();
        Map<String, Object> input = Map.of("regionCode", regionCode, "structureType", structureType,
                "materialGrade", materialGrade, "buildingArea", buildingArea);
        jdbcTemplate.update(
                "INSERT INTO budget_estimate (id, project_id, user_id, rule_version_id, result_version_id, "
                        + "input_snapshot, total_low_cents, total_high_cents) "
                        + "VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)",
                id, projectId, userId, ruleId, resultVersionId, toJson(input), low, high);
        if (usagePricing != null) {
            var snapshot = usagePricing.quote("BUDGET_ESTIMATE");
            cn.iocoder.yudao.module.infra.zhongshu.api.UsagePricingPort.requireConfirmed(snapshot, confirmation);
            usagePricing.prepareCharge(userId, snapshot, "budget_estimate", String.valueOf(id));
            usagePricing.chargePrepared(userId, "BUDGET_ESTIMATE", "budget_estimate", String.valueOf(id));
        }
        return new Estimate(id, projectId, String.valueOf(ruleId), low, high, input, DISCLAIMER, Instant.now());
    }

    public Optional<Estimate> getEstimate(long userId, long estimateId) {
        List<Estimate> rows = jdbcTemplate.query(
                "SELECT id, project_id, rule_version_id, input_snapshot::text, total_low_cents, "
                        + "total_high_cents, create_time FROM budget_estimate "
                        + "WHERE id = ? AND user_id = ? AND model = 'LEGACY_RANGE' AND deleted = FALSE",
                (rs, i) -> {
                    try {
                        var input = new com.fasterxml.jackson.databind.ObjectMapper().<Map<String, Object>>readValue(
                                rs.getString("input_snapshot"),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                        return new Estimate(rs.getLong("id"), rs.getLong("project_id"),
                                rs.getString("rule_version_id"), rs.getLong("total_low_cents"),
                                rs.getLong("total_high_cents"), input, DISCLAIMER,
                                rs.getTimestamp("create_time").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                estimateId, userId);
        return rows.stream().findFirst().filter(row -> projectService.getProject(row.projectId(), userId).isPresent());
    }

    public List<Estimate> listByProject(long userId, long projectId) {
        projectService.getProject(projectId, userId).orElseThrow(() -> exception(RESOURCE_FORBIDDEN));
        return jdbcTemplate.query(
                "SELECT id, project_id, rule_version_id, input_snapshot::text, total_low_cents, "
                        + "total_high_cents, create_time FROM budget_estimate "
                        + "WHERE project_id = ? AND user_id = ? AND model = 'LEGACY_RANGE' AND deleted = FALSE ORDER BY id DESC",
                (rs, i) -> {
                    try {
                        var input = new com.fasterxml.jackson.databind.ObjectMapper().<Map<String, Object>>readValue(
                                rs.getString("input_snapshot"),
                                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                                });
                        return new Estimate(rs.getLong("id"), rs.getLong("project_id"),
                                rs.getString("rule_version_id"), rs.getLong("total_low_cents"),
                                rs.getLong("total_high_cents"), input, DISCLAIMER,
                                rs.getTimestamp("create_time").toInstant());
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                projectId, userId);
    }

    private String toJson(Map<String, Object> value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

}
