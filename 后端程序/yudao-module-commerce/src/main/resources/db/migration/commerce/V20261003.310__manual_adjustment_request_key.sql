ALTER TABLE manual_point_adjustment ADD COLUMN request_key VARCHAR(128);

CREATE UNIQUE INDEX uk_manual_point_adjustment_request
    ON manual_point_adjustment (maker_user_id, request_key)
    WHERE request_key IS NOT NULL;
