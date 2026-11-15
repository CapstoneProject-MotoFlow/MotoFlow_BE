-- Review 13-15/11. All changes of the database design dated 14/11 that the code does not have yet.

-- 1. BR-71: a correction of a debt payment reverses its allocation rows and allocates the corrected amount again.
--    A reversed row stays for history and no longer reduces the receivable.
ALTER TABLE payment_allocation ADD COLUMN reversed_by_adjustment_id BIGINT;
ALTER TABLE payment_allocation ADD COLUMN reversed_at TIMESTAMP;
ALTER TABLE payment_allocation ADD CONSTRAINT fk_payment_allocation_reversed_by_adjustment_id
    FOREIGN KEY (reversed_by_adjustment_id) REFERENCES adjustment_request(id);
ALTER TABLE payment_allocation ADD CONSTRAINT ck_payment_allocation_reversed
    CHECK ((reversed_by_adjustment_id IS NULL) = (reversed_at IS NULL));
-- V1 declared UNIQUE (payment_id, receivable_id) unnamed; drop whichever unique constraint the table has.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'payment_allocation'::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE payment_allocation DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
CREATE UNIQUE INDEX ux_payment_allocation_active ON payment_allocation (payment_id, receivable_id)
    WHERE reversed_at IS NULL;
-- The dropped unique constraint was also the index on payment_id.
CREATE INDEX ix_payment_allocation_payment ON payment_allocation (payment_id);
CREATE INDEX ix_payment_allocation_reversed_by ON payment_allocation (reversed_by_adjustment_id);

-- 2. A document may post several movements of the same kind on one lot and status (a count line with a difference
--    and a change in damaged quantity). Posting once is guarded by the document's status under its row lock.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'stock_movement'::regclass AND contype = 'u'
    LOOP
        EXECUTE format('ALTER TABLE stock_movement DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;

-- 3. K1: a Branch Manager who took the task because no Staff was available may confirm their own result.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conname FROM pg_constraint
             WHERE conrelid = 'goods_receipt_note'::regclass AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%confirmed_by <> recorded_by%'
    LOOP
        EXECUTE format('ALTER TABLE goods_receipt_note DROP CONSTRAINT %I', c.conname);
    END LOOP;
END $$;
-- G06: required (in the service) when a line has a short, excess, wrong-item or damaged quantity.
ALTER TABLE goods_receipt_note ADD COLUMN inspection_note VARCHAR(500);

-- 4. Stock count scope by part category, the category including its subcategories.
DO $$
DECLARE c record;
BEGIN
    FOR c IN SELECT conrelid::regclass::text AS tbl, conname FROM pg_constraint
             WHERE conrelid IN ('stock_count'::regclass, 'stock_count_request'::regclass) AND contype = 'c'
               AND pg_get_constraintdef(oid) LIKE '%PRODUCTS%'
    LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I', c.tbl, c.conname);
    END LOOP;
END $$;
ALTER TABLE stock_count_request ADD COLUMN part_category_id BIGINT;
ALTER TABLE stock_count ADD COLUMN part_category_id BIGINT;
ALTER TABLE stock_count_request ADD CONSTRAINT ck_stock_count_request_scope_type
    CHECK (scope_type IN ('WHOLE', 'PRODUCTS', 'CATEGORY', 'AREA'));
ALTER TABLE stock_count ADD CONSTRAINT ck_stock_count_scope_type
    CHECK (scope_type IN ('WHOLE', 'PRODUCTS', 'CATEGORY', 'AREA'));
ALTER TABLE stock_count_request ADD CONSTRAINT ck_stock_count_request_category
    CHECK ((scope_type = 'CATEGORY') = (part_category_id IS NOT NULL));
ALTER TABLE stock_count ADD CONSTRAINT ck_stock_count_category
    CHECK ((scope_type = 'CATEGORY') = (part_category_id IS NOT NULL));
ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_part_category_id
    FOREIGN KEY (part_category_id) REFERENCES part_category(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_part_category_id
    FOREIGN KEY (part_category_id) REFERENCES part_category(id);
CREATE INDEX ix_stock_count_request_part_category_id ON stock_count_request (part_category_id);
CREATE INDEX ix_stock_count_part_category_id ON stock_count (part_category_id);

-- 5. A04: damaged goods found in a count. book_qty now includes the DAMAGED quantity of the line.
ALTER TABLE stock_count_line ADD COLUMN book_damaged_qty INTEGER NOT NULL DEFAULT 0
    CONSTRAINT ck_stock_count_line_book_damaged CHECK (book_damaged_qty >= 0);
ALTER TABLE stock_count_line ADD COLUMN damaged_qty INTEGER NOT NULL DEFAULT 0
    CONSTRAINT ck_stock_count_line_damaged CHECK (damaged_qty >= 0);
ALTER TABLE stock_count_line ADD COLUMN previous_damaged_qty INTEGER
    CONSTRAINT ck_stock_count_line_previous_damaged CHECK (previous_damaged_qty >= 0);
ALTER TABLE stock_count_line ADD CONSTRAINT ck_stock_count_line_damaged_in_counted
    CHECK (counted_qty IS NULL OR damaged_qty <= counted_qty);

-- 6. Deliverer and vehicle of a transfer, recorded at dispatch (optional).
ALTER TABLE transfer_order ADD COLUMN deliverer_name VARCHAR(100);
ALTER TABLE transfer_order ADD COLUMN vehicle_plate_no VARCHAR(20);

-- 7. The warehouse a task belongs to: the store whose Branch Manager assigns it.
ALTER TABLE warehouse_task ADD COLUMN store_id BIGINT;
UPDATE warehouse_task t SET store_id = o.store_id FROM sales_order o WHERE t.sales_order_id = o.id;
UPDATE warehouse_task t SET store_id = p.store_id FROM purchase_order p WHERE t.purchase_order_id = p.id;
UPDATE warehouse_task t SET store_id = c.store_id FROM stock_count c WHERE t.stock_count_id = c.id;
UPDATE warehouse_task t
SET store_id = CASE WHEN t.task_type = 'DISPATCH_PREPARATION' THEN o.source_store_id ELSE o.dest_store_id END
FROM transfer_order o WHERE t.transfer_order_id = o.id;
UPDATE warehouse_task t
SET store_id = CASE g.receipt_type WHEN 'SUPPLIER_DELIVERY' THEN p.store_id
                                   WHEN 'TRANSFER_IN' THEN o.dest_store_id
                                   ELSE o.source_store_id END
FROM goods_receipt_note g
     LEFT JOIN purchase_order p ON p.id = g.purchase_order_id
     LEFT JOIN transfer_order o ON o.id = g.transfer_order_id
WHERE t.goods_receipt_note_id = g.id;
ALTER TABLE warehouse_task ALTER COLUMN store_id SET NOT NULL;
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_store_id FOREIGN KEY (store_id) REFERENCES store(id);
CREATE INDEX ix_warehouse_task_store_id ON warehouse_task (store_id);