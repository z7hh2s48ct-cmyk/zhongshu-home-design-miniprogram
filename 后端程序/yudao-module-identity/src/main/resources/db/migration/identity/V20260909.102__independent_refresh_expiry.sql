-- Refresh tokens survive access-token expiry. Existing sessions retain a bounded
-- 30-day lifetime measured from their original issue time; revoked rows stay revoked.
ALTER TABLE user_session ADD COLUMN refresh_expires_at TIMESTAMPTZ;
UPDATE user_session SET refresh_expires_at = create_time + INTERVAL '30 days';
ALTER TABLE user_session ALTER COLUMN refresh_expires_at SET NOT NULL;
ALTER TABLE user_session ALTER COLUMN refresh_expires_at SET DEFAULT (now() + INTERVAL '30 days');
