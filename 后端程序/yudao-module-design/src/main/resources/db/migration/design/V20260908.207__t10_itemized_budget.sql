-- T10-02: additive only. Do not rewrite legacy prices, amounts or input snapshots.
-- Published prices/revisions are append-only in the service layer; no trigger or extension.
ALTER TABLE budget_estimate
    ADD COLUMN model VARCHAR(16) NOT NULL DEFAULT 'LEGACY_RANGE',
    ADD COLUMN saved BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN current_revision INTEGER NULL,
    ADD COLUMN idempotency_key VARCHAR(64) NULL,
    ADD COLUMN request_hash VARCHAR(64) NULL,
    ALTER COLUMN rule_version_id DROP NOT NULL,
    ALTER COLUMN total_low_cents DROP NOT NULL,
    ALTER COLUMN total_high_cents DROP NOT NULL,
    ADD CONSTRAINT ck_budget_estimate_model CHECK (
        (model = 'LEGACY_RANGE' AND rule_version_id IS NOT NULL
            AND total_low_cents IS NOT NULL AND total_high_cents IS NOT NULL AND current_revision IS NULL)
        OR (model = 'ITEMIZED_V1' AND rule_version_id IS NULL
            AND total_low_cents IS NULL AND total_high_cents IS NULL
            AND current_revision IS NOT NULL AND current_revision > 0
            AND idempotency_key IS NOT NULL AND length(trim(idempotency_key)) > 0
            AND request_hash IS NOT NULL AND request_hash ~ '^[0-9a-f]{64}$')),
    ADD CONSTRAINT uk_budget_estimate_model UNIQUE (id, model),
    ADD CONSTRAINT uk_budget_estimate_tenant UNIQUE (tenant_id, id);
CREATE UNIQUE INDEX uk_budget_estimate_request ON budget_estimate (user_id, idempotency_key)
    WHERE model = 'ITEMIZED_V1';
CREATE INDEX idx_budget_estimate_saved ON budget_estimate (user_id, project_id, id DESC)
    WHERE saved = TRUE AND deleted = FALSE;

CREATE TABLE budget_region (
    id BIGINT CONSTRAINT pk_budget_region PRIMARY KEY,
    code VARCHAR(32) NOT NULL CONSTRAINT ck_budget_region_code CHECK (code ~ '^[A-Za-z0-9_-]+$'),
    name VARCHAR(100) NOT NULL CONSTRAINT ck_budget_region_name CHECK (length(trim(name)) > 0),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_region_version CHECK (version > 0),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_region_tenant_code UNIQUE (tenant_id, code), CONSTRAINT uk_budget_region_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE budget_item (
    id BIGINT CONSTRAINT pk_budget_item PRIMARY KEY,
    code VARCHAR(64) NOT NULL CONSTRAINT ck_budget_item_code CHECK (code ~ '^[A-Z0-9_]+$'),
    name VARCHAR(100) NOT NULL CONSTRAINT ck_budget_item_name CHECK (length(trim(name)) > 0),
    category VARCHAR(16) NOT NULL CONSTRAINT ck_budget_item_category CHECK (category IN ('BODY', 'EXTERIOR')),
    source VARCHAR(24) NOT NULL CONSTRAINT ck_budget_item_source CHECK (source IN ('STANDARD', 'CUSTOM_TEMPLATE')),
    public_selectable BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_item_version CHECK (version > 0),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_item_tenant_code UNIQUE (tenant_id, code), CONSTRAINT uk_budget_item_tenant_id UNIQUE (tenant_id, id)
);

CREATE TABLE budget_option (
    id BIGINT CONSTRAINT pk_budget_option PRIMARY KEY,
    item_id BIGINT NOT NULL,
    code VARCHAR(64) NOT NULL CONSTRAINT ck_budget_option_code CHECK (code ~ '^[A-Z0-9_]+$'),
    label VARCHAR(100) NOT NULL CONSTRAINT ck_budget_option_label CHECK (length(trim(label)) > 0),
    selection_group VARCHAR(64) NOT NULL CONSTRAINT ck_budget_option_selection_group CHECK (selection_group ~ '^[A-Z0-9_]+$'),
    unit VARCHAR(16) NOT NULL CONSTRAINT ck_budget_option_unit CHECK (unit IN ('SQM', 'METER', 'PIECE', 'SET', 'HOUSEHOLD', 'ITEM')),
    quantity_source VARCHAR(24) NOT NULL CONSTRAINT ck_budget_option_quantity_source CHECK (quantity_source IN (
        'FOOTPRINT_AREA', 'BUILDING_AREA', 'ROOF_AREA', 'WINDOW_AREA', 'PROJECT_QUANTITY', 'FIXED_ONE')),
    quantity_key VARCHAR(64),
    source_reference VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_option_version CHECK (version > 0),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_option_item_id_code UNIQUE (item_id, code), CONSTRAINT uk_budget_option_tenant_id UNIQUE (tenant_id, id), CONSTRAINT uk_budget_option_tenant_item_id_id UNIQUE (tenant_id, item_id, id),
    CONSTRAINT fk_budget_option_item_id FOREIGN KEY (tenant_id, item_id) REFERENCES budget_item (tenant_id, id),
    CONSTRAINT ck_budget_option_quantity_key CHECK (quantity_source <> 'PROJECT_QUANTITY' OR
        (quantity_key IS NOT NULL AND quantity_key ~ '^[A-Z0-9_]+$')),
    CONSTRAINT ck_budget_option_area_unit CHECK (quantity_source NOT IN ('FOOTPRINT_AREA', 'BUILDING_AREA', 'ROOF_AREA', 'WINDOW_AREA') OR unit = 'SQM')
);

CREATE TABLE budget_item_price (
    id BIGINT CONSTRAINT pk_budget_item_price PRIMARY KEY,
    region_id BIGINT NOT NULL,
    option_id BIGINT NOT NULL,
    unit_price_cents BIGINT CONSTRAINT ck_budget_item_price_unit_price_cents CHECK (unit_price_cents BETWEEN 0 AND 100000000),
    free_reason VARCHAR(500),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CONSTRAINT ck_budget_item_price_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'DISABLED')),
    effective_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_item_price_version CHECK (version > 0),
    published_by BIGINT,
    published_at TIMESTAMPTZ,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_item_price_tenant_id UNIQUE (tenant_id, id), CONSTRAINT uk_budget_item_price_tenant_option_id_id UNIQUE (tenant_id, option_id, id),
    CONSTRAINT fk_budget_item_price_region_id FOREIGN KEY (tenant_id, region_id) REFERENCES budget_region (tenant_id, id),
    CONSTRAINT fk_budget_item_price_option_id FOREIGN KEY (tenant_id, option_id) REFERENCES budget_option (tenant_id, id),
    CONSTRAINT ck_budget_item_price_free_reason CHECK (unit_price_cents IS DISTINCT FROM 0 OR (free_reason IS NOT NULL AND length(trim(free_reason)) > 0)),
    CONSTRAINT ck_budget_item_price_effective_range CHECK (expires_at IS NULL OR (effective_at IS NOT NULL AND expires_at > effective_at)),
    CONSTRAINT ck_budget_item_price_publication CHECK (status <> 'PUBLISHED' OR (unit_price_cents IS NOT NULL AND effective_at IS NOT NULL
        AND published_by IS NOT NULL AND published_at IS NOT NULL))
);
-- Publication must lock the region row before checking overlap (T10-04); this is not an overlap constraint.
CREATE INDEX idx_budget_price_effective ON budget_item_price (tenant_id, region_id, option_id, effective_at)
    WHERE status = 'PUBLISHED' AND deleted = FALSE;

CREATE TABLE budget_revision (
    id BIGINT CONSTRAINT pk_budget_revision PRIMARY KEY,
    estimate_id BIGINT NOT NULL,
    model VARCHAR(16) NOT NULL DEFAULT 'ITEMIZED_V1' CONSTRAINT ck_budget_revision_model CHECK (model = 'ITEMIZED_V1'),
    revision_no INTEGER NOT NULL CONSTRAINT ck_budget_revision_revision_no CHECK (revision_no > 0),
    input_snapshot JSONB NOT NULL CONSTRAINT ck_budget_revision_input_snapshot CHECK (jsonb_typeof(input_snapshot) = 'object'),
    completeness VARCHAR(16) NOT NULL CONSTRAINT ck_budget_revision_completeness CHECK (completeness IN ('COMPLETE', 'INCOMPLETE')),
    body_subtotal_cents BIGINT NOT NULL CONSTRAINT ck_budget_revision_body_subtotal_cents CHECK (body_subtotal_cents BETWEEN 0 AND 10000000000),
    exterior_subtotal_cents BIGINT NOT NULL CONSTRAINT ck_budget_revision_exterior_subtotal_cents CHECK (exterior_subtotal_cents BETWEEN 0 AND 10000000000),
    priced_subtotal_cents BIGINT NOT NULL CONSTRAINT ck_budget_revision_priced_subtotal_cents CHECK (priced_subtotal_cents BETWEEN 0 AND 10000000000),
    total_cents BIGINT,
    actor_type VARCHAR(16) NOT NULL CONSTRAINT ck_budget_revision_actor_type CHECK (actor_type IN ('USER', 'ADMIN')),
    actor_id BIGINT NOT NULL,
    change_reason VARCHAR(500) NOT NULL CONSTRAINT ck_budget_revision_change_reason CHECK (length(trim(change_reason)) > 0),
    idempotency_key VARCHAR(64) NOT NULL CONSTRAINT ck_budget_revision_idempotency_key CHECK (length(trim(idempotency_key)) > 0),
    request_hash VARCHAR(64) NOT NULL CONSTRAINT ck_budget_revision_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_revision_estimate_revision_no UNIQUE (estimate_id, revision_no), CONSTRAINT uk_budget_revision_tenant_id UNIQUE (tenant_id, id),
    CONSTRAINT uk_budget_revision_estimate_id_total UNIQUE (estimate_id, id, completeness, total_cents),
    CONSTRAINT uk_budget_revision_request UNIQUE (actor_type, actor_id, idempotency_key),
    CONSTRAINT fk_budget_revision_model FOREIGN KEY (estimate_id, model) REFERENCES budget_estimate (id, model),
    CONSTRAINT fk_budget_revision_estimate_id FOREIGN KEY (tenant_id, estimate_id) REFERENCES budget_estimate (tenant_id, id),
    CONSTRAINT ck_budget_revision_subtotal CHECK (priced_subtotal_cents = body_subtotal_cents + exterior_subtotal_cents),
    CONSTRAINT ck_budget_revision_total_presence CHECK ((completeness = 'COMPLETE' AND total_cents IS NOT NULL AND total_cents = priced_subtotal_cents)
        OR (completeness = 'INCOMPLETE' AND total_cents IS NULL))
);
-- Insert master + first revision in one transaction; don't permit a committed dangling current revision.
ALTER TABLE budget_estimate ADD CONSTRAINT fk_budget_current_revision
    FOREIGN KEY (id, current_revision) REFERENCES budget_revision (estimate_id, revision_no)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE budget_line (
    id BIGINT CONSTRAINT pk_budget_line PRIMARY KEY,
    revision_id BIGINT NOT NULL,
    line_key VARCHAR(64) NOT NULL CONSTRAINT ck_budget_line_line_key CHECK (length(trim(line_key)) > 0),
    item_id BIGINT,
    option_id BIGINT,
    price_version_id BIGINT,
    item_code VARCHAR(64) NOT NULL,
    public_name VARCHAR(100) NOT NULL CONSTRAINT ck_budget_line_public_name CHECK (length(trim(public_name)) > 0),
    option_label VARCHAR(100),
    category VARCHAR(16) NOT NULL CONSTRAINT ck_budget_line_category CHECK (category IN ('BODY', 'EXTERIOR')),
    source VARCHAR(24) NOT NULL CONSTRAINT ck_budget_line_source CHECK (source IN ('STANDARD', 'CUSTOM_TEMPLATE', 'PROJECT_CUSTOM')),
    unit VARCHAR(16) NOT NULL CONSTRAINT ck_budget_line_unit CHECK (unit IN ('SQM', 'METER', 'PIECE', 'SET', 'HOUSEHOLD', 'ITEM')),
    -- Unconstrained NUMERIC + explicit precision check rejects, rather than silently rounds, excess scale.
    quantity NUMERIC CONSTRAINT ck_budget_line_quantity CHECK (quantity > 0 AND quantity <= 1000000 AND scale(quantity) <= 4),
    unit_price_cents BIGINT CONSTRAINT ck_budget_line_unit_price_cents CHECK (unit_price_cents BETWEEN 0 AND 100000000),
    amount_cents BIGINT CONSTRAINT ck_budget_line_amount_cents CHECK (amount_cents BETWEEN 0 AND 10000000000),
    status VARCHAR(24) NOT NULL CONSTRAINT ck_budget_line_status CHECK (status IN ('PRICED', 'MISSING_PRICE', 'MISSING_QUANTITY', 'MISSING_BOTH', 'EXCLUDED')),
    free_reason VARCHAR(500),
    excluded_reason VARCHAR(500),
    internal_note VARCHAR(500),
    pricing_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb CONSTRAINT ck_budget_line_pricing_snapshot CHECK (jsonb_typeof(pricing_snapshot) = 'object'),
    sort_order INTEGER NOT NULL DEFAULT 0,
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_line_revision_line_key UNIQUE (revision_id, line_key), CONSTRAINT uk_budget_line_revision_option_id UNIQUE (revision_id, option_id),
    CONSTRAINT fk_budget_line_revision_id FOREIGN KEY (tenant_id, revision_id) REFERENCES budget_revision (tenant_id, id),
    CONSTRAINT fk_budget_line_item_id FOREIGN KEY (tenant_id, item_id) REFERENCES budget_item (tenant_id, id),
    CONSTRAINT fk_budget_line_item_id_option_id FOREIGN KEY (tenant_id, item_id, option_id) REFERENCES budget_option (tenant_id, item_id, id),
    CONSTRAINT fk_budget_line_option_id_price_version_id FOREIGN KEY (tenant_id, option_id, price_version_id) REFERENCES budget_item_price (tenant_id, option_id, id),
    CONSTRAINT ck_budget_line_source_reference CHECK ((source = 'PROJECT_CUSTOM' AND item_id IS NULL AND option_id IS NULL AND price_version_id IS NULL)
        OR (source IN ('STANDARD', 'CUSTOM_TEMPLATE') AND item_id IS NOT NULL)),
    CONSTRAINT ck_budget_line_price_option CHECK (price_version_id IS NULL OR option_id IS NOT NULL),
    CONSTRAINT ck_budget_line_count_quantity CHECK (quantity IS NULL OR unit IN ('SQM', 'METER') OR (quantity <= 100000 AND quantity = trunc(quantity))),
    CONSTRAINT ck_budget_line_free_reason CHECK (unit_price_cents IS DISTINCT FROM 0 OR (free_reason IS NOT NULL AND length(trim(free_reason)) > 0)),
    CONSTRAINT ck_budget_line_state_amount CHECK (
        (status = 'PRICED' AND quantity IS NOT NULL AND unit_price_cents IS NOT NULL
            AND amount_cents IS NOT NULL AND amount_cents = round(quantity * unit_price_cents))
        OR (status = 'MISSING_PRICE' AND quantity IS NOT NULL AND unit_price_cents IS NULL AND amount_cents IS NULL)
        OR (status = 'MISSING_QUANTITY' AND quantity IS NULL AND unit_price_cents IS NOT NULL AND amount_cents IS NULL)
        OR (status = 'MISSING_BOTH' AND quantity IS NULL AND unit_price_cents IS NULL AND amount_cents IS NULL)
        OR (status = 'EXCLUDED' AND amount_cents IS NULL
            AND excluded_reason IS NOT NULL AND length(trim(excluded_reason)) > 0))
);

CREATE TABLE budget_quote (
    id BIGINT CONSTRAINT pk_budget_quote PRIMARY KEY,
    estimate_id BIGINT NOT NULL,
    revision_id BIGINT NOT NULL,
    completeness VARCHAR(16) NOT NULL DEFAULT 'COMPLETE' CONSTRAINT ck_budget_quote_completeness CHECK (completeness = 'COMPLETE'),
    quote_version INTEGER NOT NULL CONSTRAINT ck_budget_quote_quote_version CHECK (quote_version > 0),
    calculated_total_cents BIGINT NOT NULL CONSTRAINT ck_budget_quote_calculated_total_cents CHECK (calculated_total_cents BETWEEN 0 AND 10000000000),
    adjustment_cents BIGINT NOT NULL CONSTRAINT ck_budget_quote_adjustment_cents CHECK (adjustment_cents BETWEEN -10000000000 AND 10000000000),
    final_price_cents BIGINT NOT NULL CONSTRAINT ck_budget_quote_final_price_cents CHECK (final_price_cents BETWEEN 0 AND 10000000000),
    reason VARCHAR(500) NOT NULL CONSTRAINT ck_budget_quote_reason CHECK (length(trim(reason)) > 0),
    status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' CONSTRAINT ck_budget_quote_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'WITHDRAWN')),
    version INTEGER NOT NULL DEFAULT 1 CONSTRAINT ck_budget_quote_version CHECK (version > 0),
    actor_id BIGINT NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL CONSTRAINT ck_budget_quote_idempotency_key CHECK (length(trim(idempotency_key)) > 0),
    request_hash VARCHAR(64) NOT NULL CONSTRAINT ck_budget_quote_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    published_by BIGINT, published_at TIMESTAMPTZ,
    withdrawn_by BIGINT, withdrawn_at TIMESTAMPTZ, withdrawal_reason VARCHAR(500),
    tenant_id BIGINT NOT NULL DEFAULT 0,
    creator VARCHAR(64) DEFAULT '', create_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    updater VARCHAR(64) DEFAULT '', update_time TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_budget_quote_estimate_quote_version UNIQUE (estimate_id, quote_version), CONSTRAINT uk_budget_quote_request UNIQUE (actor_id, idempotency_key),
    CONSTRAINT fk_budget_quote_revision_id FOREIGN KEY (tenant_id, revision_id) REFERENCES budget_revision (tenant_id, id),
    CONSTRAINT fk_budget_quote_revision_total FOREIGN KEY (estimate_id, revision_id, completeness, calculated_total_cents)
        REFERENCES budget_revision (estimate_id, id, completeness, total_cents),
    CONSTRAINT ck_budget_quote_total CHECK (final_price_cents = calculated_total_cents + adjustment_cents),
    CONSTRAINT ck_budget_quote_publication CHECK (status = 'DRAFT' OR (published_by IS NOT NULL AND published_at IS NOT NULL)),
    CONSTRAINT ck_budget_quote_withdrawal CHECK (status <> 'WITHDRAWN' OR (withdrawn_by IS NOT NULL AND withdrawn_at IS NOT NULL
        AND withdrawal_reason IS NOT NULL AND length(trim(withdrawal_reason)) > 0))
);

-- Only stable catalog identities are seeded. No region, enabled option, price or customer budget is created.
INSERT INTO budget_item (id, code, name, category, source, sort_order) VALUES
    (207001, 'FOUNDATION', '地基基础', 'BODY', 'STANDARD', 10),
    (207002, 'STRUCTURE', '主体结构', 'BODY', 'STANDARD', 20),
    (207003, 'ROOF', '屋面结构', 'BODY', 'STANDARD', 30),
    (207004, 'DECORATION', '装饰构件', 'EXTERIOR', 'STANDARD', 40),
    (207005, 'DOORS_WINDOWS', '门窗', 'EXTERIOR', 'STANDARD', 50),
    (207006, 'WALL_PAINT', '外墙漆', 'EXTERIOR', 'STANDARD', 60),
    (207007, 'CULTURE_STONE', '文化石', 'EXTERIOR', 'STANDARD', 70),
    (207008, 'LIGHTING', '灯具', 'EXTERIOR', 'STANDARD', 80),
    (207009, 'WATERPROOF_LIGHTNING', '防水防雷', 'EXTERIOR', 'STANDARD', 90),
    (207010, 'INSURANCE', '保险', 'EXTERIOR', 'STANDARD', 100);
