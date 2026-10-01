DROP INDEX IF EXISTS idx_generation_price_rule_stage_resolution;
-- 回滚后的旧代码只按 stage 解析价格；先停用 4K，避免把 4K 单价误当成旧版 2K 单价。
-- 规则行必须保留，历史 ai_task_charge 的 price_rule_id 快照仍需可追溯。
UPDATE generation_price_rule
SET status = 'RETIRED', version = version + 1, update_time = now()
WHERE resolution = '4K' AND status = 'ACTIVE';
ALTER TABLE generation_price_rule DROP CONSTRAINT IF EXISTS ck_generation_price_rule_cost;
ALTER TABLE generation_price_rule ADD CONSTRAINT ck_generation_price_rule_cost CHECK (unit_point_cost > 0);
ALTER TABLE generation_price_rule DROP CONSTRAINT IF EXISTS ck_generation_price_rule_count;
ALTER TABLE generation_price_rule ADD CONSTRAINT ck_generation_price_rule_count CHECK (min_count >= 1 AND max_count >= min_count);
ALTER TABLE generation_price_rule DROP CONSTRAINT IF EXISTS ck_generation_price_rule_resolution;
ALTER TABLE generation_price_rule DROP COLUMN IF EXISTS resolution;
