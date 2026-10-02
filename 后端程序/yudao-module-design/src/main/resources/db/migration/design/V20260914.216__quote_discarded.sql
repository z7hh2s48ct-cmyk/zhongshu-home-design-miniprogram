-- 报价状态机补「DISCARDED」（作废草稿）：解决废弃/stale 草稿只增不减的死状态问题。
-- 存量数据不受影响；业主端读取路径本就只取 PUBLISHED/WITHDRAWN，作废件永不外泄。
ALTER TABLE budget_quote DROP CONSTRAINT IF EXISTS ck_budget_quote_status;
ALTER TABLE budget_quote ADD CONSTRAINT ck_budget_quote_status
    CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN', 'DISCARDED'));
