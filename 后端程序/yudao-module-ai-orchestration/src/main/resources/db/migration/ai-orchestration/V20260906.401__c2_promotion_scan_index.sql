-- C2 候选自动晋升：驱动器每轮回扫「已结算 + 有 ACCEPTED 结果 + 无候选记录」的任务（24h 窗口）。
-- 无此索引时 status 前缀只能命中全部终态行、update_time 逐行残差过滤，历史任务单调累积后成为常驻慢查询。
CREATE INDEX IF NOT EXISTS idx_ai_job_settled_recent
    ON ai_job (update_time, id)
    WHERE deleted = FALSE AND status IN ('SUCCEEDED', 'PARTIALLY_SUCCEEDED');
