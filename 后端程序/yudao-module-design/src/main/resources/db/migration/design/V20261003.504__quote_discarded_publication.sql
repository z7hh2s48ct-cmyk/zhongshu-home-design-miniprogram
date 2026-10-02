-- A discarded quote is always an unpublished draft. Keep publication evidence mandatory
-- for PUBLISHED/WITHDRAWN rows while allowing the draft discard transition to remain honest.
ALTER TABLE budget_quote DROP CONSTRAINT IF EXISTS ck_budget_quote_publication;
ALTER TABLE budget_quote ADD CONSTRAINT ck_budget_quote_publication
    CHECK ((status IN ('DRAFT', 'DISCARDED') AND published_by IS NULL AND published_at IS NULL)
        OR (status IN ('PUBLISHED', 'WITHDRAWN') AND published_by IS NOT NULL AND published_at IS NOT NULL));
