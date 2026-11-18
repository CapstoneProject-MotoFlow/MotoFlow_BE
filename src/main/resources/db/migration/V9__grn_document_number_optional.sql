-- PM 17/11 (Q1): a supplier delivery may be recorded without a delivery document number. V1 declared the check
-- unnamed; drop it by its content. UNIQUE (purchase_order_id, delivery_document_no) stays: PostgreSQL allows several NULLs.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'goods_receipt_note'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%delivery_document_no IS NOT NULL%'
    LOOP
        EXECUTE format('ALTER TABLE goods_receipt_note DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
