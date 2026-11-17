-- 17/11 (PM N1): replenishment request drafts saved on the server, as sales order drafts (V7). DRAFT stays outside
-- coordination until it is submitted.
-- V1 declared the CHECK on replenishment_request.status inline, so PostgreSQL named it; drop whichever check lists the
-- statuses.
DO $$
DECLARE
    found record;
BEGIN
    FOR found IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'replenishment_request'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%AWAITING_COORDINATION%'
    LOOP
        EXECUTE format('ALTER TABLE replenishment_request DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;
ALTER TABLE replenishment_request ADD CONSTRAINT replenishment_request_status_check
    CHECK (status IN ('DRAFT', 'AWAITING_COORDINATION', 'IN_PROGRESS', 'CLOSED'));
CREATE INDEX idx_replenishment_request_draft_creator ON replenishment_request (created_by, created_at DESC)
    WHERE status = 'DRAFT';
