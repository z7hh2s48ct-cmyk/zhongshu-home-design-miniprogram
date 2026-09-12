ALTER TABLE user_message ADD COLUMN source_event_id BIGINT;
CREATE UNIQUE INDEX uk_user_message_source_recipient ON user_message(source_event_id, user_id)
WHERE source_event_id IS NOT NULL;
