-- Existing prices retain their original meaning as 2K. 4K stays unavailable until an admin adds a rule.
ALTER TABLE generation_price_rule
    ADD COLUMN resolution VARCHAR(8) NOT NULL DEFAULT '2K';

ALTER TABLE generation_price_rule
    ADD CONSTRAINT ck_generation_price_rule_resolution CHECK (resolution IN ('2K', '4K'));

CREATE INDEX idx_generation_price_rule_stage_resolution
    ON generation_price_rule (stage, resolution, status, effective_at DESC);

ALTER TABLE generation_price_rule DROP CONSTRAINT ck_generation_price_rule_cost;
ALTER TABLE generation_price_rule
    ADD CONSTRAINT ck_generation_price_rule_cost CHECK (unit_point_cost BETWEEN 1 AND 1000000000);

ALTER TABLE generation_price_rule DROP CONSTRAINT ck_generation_price_rule_count;
ALTER TABLE generation_price_rule
    ADD CONSTRAINT ck_generation_price_rule_count CHECK (min_count >= 1 AND max_count BETWEEN min_count AND 4);
