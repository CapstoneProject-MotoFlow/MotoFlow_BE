-- Lecturer feedback 12/11: the Branch Manager gives the receiving of a purchase order (SUPPLIER_RECEIVING) or of a
-- transfer at its destination (TRANSFER_RECEIVING) to a Staff member, who records the goods receipt note and then
-- inspects it. A receiving task belongs to the purchase order or to the transfer order, like DISPATCH_PREPARATION.
ALTER TABLE warehouse_task ADD COLUMN purchase_order_id BIGINT;
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_purchase_order_id
    FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id);
CREATE INDEX ix_warehouse_task_purchase_order_id ON warehouse_task (purchase_order_id);

-- V1 created the task_type CHECK and the five table CHECKs unnamed; drop them all (keeping the status CHECK) and add
-- them back with names.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'warehouse_task'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) NOT LIKE '%status%'
    LOOP
        EXECUTE format('ALTER TABLE warehouse_task DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_type CHECK (task_type IN ('PICKING', 'INSPECTION',
    'DISPATCH_PREPARATION', 'COUNTING', 'SUPPLIER_RECEIVING', 'TRANSFER_RECEIVING'));
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_one_document
    CHECK (num_nonnulls(sales_order_id, goods_receipt_note_id, transfer_order_id, stock_count_id, purchase_order_id) = 1);
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_sales_order
    CHECK ((task_type = 'PICKING') = (sales_order_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_goods_receipt_note
    CHECK ((task_type = 'INSPECTION') = (goods_receipt_note_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_transfer_order
    CHECK ((task_type IN ('DISPATCH_PREPARATION', 'TRANSFER_RECEIVING')) = (transfer_order_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_stock_count
    CHECK ((task_type = 'COUNTING') = (stock_count_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT ck_warehouse_task_purchase_order
    CHECK ((task_type = 'SUPPLIER_RECEIVING') = (purchase_order_id IS NOT NULL));

-- Still one open task (ASSIGNED or RESULT_SUBMITTED) per document and task type.
DROP INDEX ux_warehouse_task_4;
CREATE UNIQUE INDEX ux_warehouse_task_4 ON warehouse_task (task_type, sales_order_id, goods_receipt_note_id,
    transfer_order_id, stock_count_id, purchase_order_id) NULLS NOT DISTINCT WHERE status IN ('ASSIGNED', 'RESULT_SUBMITTED');
