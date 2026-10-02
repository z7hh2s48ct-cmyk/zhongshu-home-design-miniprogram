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

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.module.commerce.enums.ErrorCodeConstants.PRICE_RULE_CHANGED;

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
        Snapshot current;
        try {
            current = quote(snapshot.product());
        } catch (IllegalStateException noActiveRule) {
            throw exception(PRICE_RULE_CHANGED);
        }
        if (current.ruleId() != snapshot.ruleId() || current.ruleVersion() != snapshot.ruleVersion()
                || current.pointCost() != snapshot.pointCost()) {
            throw exception(PRICE_RULE_CHANGED);
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

    @Override
    public Long chargedPointCost(String product, String bizType, String bizId) {
        try {
            var rows = jdbc.queryForList("SELECT point_cost FROM service_usage_charge "
                    + "WHERE product = ? AND biz_type = ? AND biz_id = ? AND state = 'CHARGED' AND deleted = FALSE",
                product, bizType, bizId);
            return rows.isEmpty() ? null : ((Number) rows.get(0).get("point_cost")).longValue();
        } catch (org.springframework.dao.DataAccessException error) {
            log.error("[chargedPointCost][业务扣点查询失败 product={} type={} id={}]", product, bizType, bizId, error);
            return null;
        }
    }

    private Map<String,Object> lockPrompt(long userId, String bizId) {
        return jdbc.queryForMap("SELECT point_cost,state,reservation_token,reservation_expires_at,call_receipt_id,call_response_hash "
                + "FROM service_usage_charge WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=? AND deleted=FALSE FOR UPDATE",userId,bizId);
    }

    @Override public String reservePrompt(long userId, String bizId, long token) {
        return tx.execute(transaction -> {
            var row = lockPrompt(userId,bizId);
            String state = (String)row.get("state");
            long amount = ((Number)row.get("point_cost")).longValue();
            if ("CHARGED".equals(state) || "UNKNOWN".equals(state)) throw new IllegalStateException("Prompt outcome requires reconciliation; do not repeat supplier call");
            if ("RESERVED".equals(state)) {
                if (((Number)row.get("reservation_token")).longValue()==token) return "RESERVED";
                var expires = (java.sql.Timestamp)row.get("reservation_expires_at");
                if (expires == null || expires.toInstant().isAfter(Instant.now())) throw new IllegalStateException("Prompt reservation still held");
                ledger.releaseReserve(userId,amount);
            }
            ledger.reserve(userId,amount,"ai_prompt",bizId);
            jdbc.update("UPDATE service_usage_charge SET state='RESERVED',reservation_token=?,reservation_expires_at=now()+interval '2 minutes',update_time=now() WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",token,userId,bizId);
            return "RESERVED";
        });
    }

    @Override public boolean finishPrompt(long userId,String bizId,long token,String outcome) {
        if (!List.of("DISPATCHED","SUCCEEDED","UNKNOWN","NOT_SENT").contains(outcome)) throw new IllegalArgumentException("Invalid prompt outcome");
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            var row = lockPrompt(userId,bizId);String state=(String)row.get("state");
            if ("CHARGED".equals(state)) {
                if (!"SUCCEEDED".equals(outcome)) throw new IllegalStateException("Confirmed prompt charge cannot be released");
                return false;
            }
            if (row.get("reservation_token")==null || ((Number)row.get("reservation_token")).longValue()!=token) {
                if ("PENDING".equals(state) && "NOT_SENT".equals(outcome)) return false;
                throw new IllegalStateException("Prompt reservation token mismatch");
            }
            long amount=((Number)row.get("point_cost")).longValue();
            if ("NOT_SENT".equals(outcome)) {
                ledger.releaseReserve(userId,amount);
                jdbc.update("UPDATE service_usage_charge SET state='PENDING',reservation_token=NULL,reservation_expires_at=NULL,update_time=now() WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",userId,bizId);
            } else if ("SUCCEEDED".equals(outcome)) {
                long id=ledger.consumeReserve(userId,amount,"AI_PROMPT_DEBIT","ai_job",bizId,"USAGE:AI_PROMPT:ai_job:"+bizId);
                jdbc.update("UPDATE service_usage_charge SET state='CHARGED',ledger_id=?,reservation_expires_at=NULL,update_time=now() WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",id,userId,bizId);
            } else {
                jdbc.update("UPDATE service_usage_charge SET state='UNKNOWN',reservation_expires_at=NULL,update_time=now() WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",userId,bizId);
            }
            return true;
        }));
    }


    @Override public boolean finishPromptForCurrentAttempt(long userId,String bizId,long token,String outcome,
                                                           String callId,String responseHash) {
        if (!java.util.Objects.equals("plan-" + bizId, callId)) throw new IllegalArgumentException("Prompt call identity mismatch");
        if ("SUCCEEDED".equals(outcome) && (responseHash==null || !responseHash.matches("[a-f0-9]{64}"))) {
            throw new IllegalArgumentException("Prompt response receipt required");
        }
        return Boolean.TRUE.equals(tx.execute(transaction -> {
            var row=lockPrompt(userId,bizId);
            if (row.get("call_receipt_id")!=null && !java.util.Objects.equals(row.get("call_receipt_id"),callId)) {
                throw new IllegalStateException("Prompt call receipt conflict");
            }
            if ("SUCCEEDED".equals(outcome)) {
                if (row.get("call_response_hash")!=null && !java.util.Objects.equals(row.get("call_response_hash"),responseHash)) {
                    throw new IllegalStateException("Prompt response receipt conflict");
                }
                if ("UNKNOWN".equals(row.get("state")) && row.get("call_receipt_id")!=null
                        && ((Number)row.get("reservation_token")).longValue()!=token) {
                    // The caller already owns the active fenced job lease. Transfer confirmation only,
                    // never release the uncertain hold or grant another supplier call.
                    jdbc.update("UPDATE service_usage_charge SET reservation_token=? WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",token,userId,bizId);
                }
            }
            boolean result=finishPrompt(userId,bizId,token,outcome);
            if ("DISPATCHED".equals(outcome) || "SUCCEEDED".equals(outcome)) {
                jdbc.update("UPDATE service_usage_charge SET call_receipt_id=?,call_response_hash=COALESCE(?,call_response_hash) WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=?",callId,responseHash,userId,bizId);
            }
            return result;
        }));
    }

    @Override public void releaseUnsentPrompt(long userId,String bizId) {
        tx.execute(status -> {
            var rows=jdbc.queryForList("SELECT reservation_token FROM service_usage_charge WHERE product='AI_PROMPT' AND user_id=? AND biz_type='ai_job' AND biz_id=? AND state='RESERVED' AND deleted=FALSE FOR UPDATE",userId,bizId);
            if (!rows.isEmpty()) finishPrompt(userId,bizId,((Number)rows.get(0).get("reservation_token")).longValue(),"NOT_SENT");
            return null;
        });
    }

    public List<Map<String,Object>> pendingPrompts() {
        return jdbc.queryForList("SELECT user_id::text AS user_id,biz_id,point_cost,state,reservation_token::text AS reservation_token,update_time FROM service_usage_charge WHERE product='AI_PROMPT' AND state IN ('RESERVED','UNKNOWN') AND deleted=FALSE ORDER BY update_time LIMIT 100");
    }

    public boolean reconcilePrompt(String bizId, boolean called) {
        var row=jdbc.queryForMap("SELECT user_id,reservation_token,state FROM service_usage_charge WHERE product='AI_PROMPT' AND biz_type='ai_job' AND biz_id=? AND deleted=FALSE",bizId);
        if (("CHARGED".equals(row.get("state")) && called) || ("PENDING".equals(row.get("state")) && !called)) return false;
        if (row.get("reservation_token")==null) throw new IllegalStateException("No call reservation to reconcile");
        return finishPrompt(((Number)row.get("user_id")).longValue(),bizId,((Number)row.get("reservation_token")).longValue(),called?"SUCCEEDED":"NOT_SENT");
    }

    private static void requireProduct(String product) {
        if (!List.of("BUDGET_ESTIMATE", "AI_PROMPT").contains(product)) {
            throw new IllegalArgumentException("业务价格类型无效");
        }
    }
}
