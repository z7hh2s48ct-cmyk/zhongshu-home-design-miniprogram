package cn.iocoder.yudao.module.infra.zhongshu.api;

/** 可独立计价的业务调用；commerce 实现价格快照和幂等扣点。 */
public interface UsagePricingPort {
    default String reservePrompt(long userId, String bizId, long token) { throw new UnsupportedOperationException("Prompt reservation not wired"); }
    default boolean finishPrompt(long userId, String bizId, long token, String outcome) { throw new UnsupportedOperationException("Prompt reservation not wired"); }
    default void releaseUnsentPrompt(long userId, String bizId) { }

    record Confirmation(String product, String ruleId, long ruleVersion) { }

    record Snapshot(String product, long ruleId, long ruleVersion, long pointCost) { }

    static void requireConfirmed(Snapshot snapshot, Confirmation confirmation) {
        if (confirmation == null || !snapshot.product().equals(confirmation.product())
                || !String.valueOf(snapshot.ruleId()).equals(confirmation.ruleId())
                || snapshot.ruleVersion() != confirmation.ruleVersion()) {
            throw new cn.iocoder.yudao.framework.common.exception.ServiceException(
                    1_072_000_001, "计价规则已更新，请确认新价格后重试");
        }
    }

    Snapshot quote(String product);

    void prepareCharge(long userId, Snapshot snapshot, String bizType, String bizId);

    /** 锁定待扣记录并原子扣点；同业务键重复调用只返回 false，不重复扣款。 */
    boolean chargePrepared(long userId, String product, String bizType, String bizId);

    /**
     * 查询业务键的已实扣点数；无扣点记录或尚未实际扣点（PENDING/UNKNOWN）返回 null。
     * 供管理端费用明细展示，只读不改账。
     */
    default Long chargedPointCost(String product, String bizType, String bizId) { return null; }
    /** Called only after the orchestration service has locked and verified the current active job lease. */
    default boolean finishPromptForCurrentAttempt(long userId, String bizId, long token, String outcome,
                                                   String callId, String responseHash) {
        return finishPrompt(userId, bizId, token, outcome);
    }

}
