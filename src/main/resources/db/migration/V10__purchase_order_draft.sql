-- PM 17/11 (D1): purchase order drafts saved on the server, like sales drafts (V7). DRAFT stays outside every PO
-- screen until it is submitted. V1 declared the status check unnamed; drop it by its content.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'purchase_order'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%AWAITING_DELIVERY%'
    LOOP
        EXECUTE format('ALTER TABLE purchase_order DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
ALTER TABLE purchase_order ADD CONSTRAINT ck_purchase_order_status
    CHECK (status IN ('DRAFT', 'AWAITING_DELIVERY', 'RECEIVING', 'FULLY_RECEIVED', 'CANCELLED'));
CREATE INDEX idx_purchase_order_draft_creator ON purchase_order (created_by, created_at DESC) WHERE status = 'DRAFT';
