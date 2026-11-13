-- 13/11 (PM): sales drafts saved on the server. DRAFT stays outside the order flow until it is submitted.
-- V1 declared the CHECK on sales_order.status inline, so PostgreSQL named it; drop whichever check lists the statuses.
DO $$
DECLARE
    found record;
BEGIN
    FOR found IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'sales_order'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%AWAITING_PICKING%'
    LOOP
        EXECUTE format('ALTER TABLE sales_order DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;
ALTER TABLE sales_order ADD CONSTRAINT sales_order_status_check
    CHECK (status IN ('DRAFT', 'AWAITING_PICKING', 'PICKED', 'CONFIRMED', 'COMPLETED', 'CANCELLED'));
CREATE INDEX idx_sales_order_draft_creator ON sales_order (created_by, created_at DESC) WHERE status = 'DRAFT';
