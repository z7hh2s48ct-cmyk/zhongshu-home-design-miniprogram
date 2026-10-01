CREATE TABLE design_project_request (
    user_id BIGINT NOT NULL,
    request_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    project_id BIGINT NULL,
    create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, request_key)
);
