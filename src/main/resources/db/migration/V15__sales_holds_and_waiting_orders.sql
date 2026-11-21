-- GVHD 20/11: the only migration of the lecturer review. No new table and one new column; the rest widens CHECKs.

-- PR 1: AWAITING_STOCK is a waiting order. It holds nothing, is not picked and takes no money until it is released.
-- Holds are computed on read from the open orders (BR-33), so no column stores them.
ALTER TABLE sales_order DROP CONSTRAINT sales_order_status_check;
ALTER TABLE sales_order ADD CONSTRAINT sales_order_status_check
    CHECK (status IN ('DRAFT', 'AWAITING_STOCK', 'AWAITING_PICKING', 'PICKED', 'CONFIRMED', 'COMPLETED',
                      'CANCELLED'));

CREATE INDEX idx_sales_order_open_by_store ON sales_order (store_id)
    WHERE status IN ('AWAITING_PICKING', 'PICKED', 'CONFIRMED');

-- PR 5: a lot made from a supplier discrepancy (dented goods taken as the variant, a wrong item taken as the part
-- received). It has the supplier of the purchase order and neither a receipt line nor a stock count.
-- V1 declared these CHECKs without names, so they are found by their definition.
DO $$
DECLARE
    found RECORD;
BEGIN
    FOR found IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'lot'::regclass AND contype = 'c' AND pg_get_constraintdef(oid) LIKE '%source_type%'
    LOOP
        EXECUTE format('ALTER TABLE lot DROP CONSTRAINT %I', found.conname);
    END LOOP;
END $$;
ALTER TABLE lot ADD CONSTRAINT lot_source_type_check
    CHECK (source_type IN ('RECEIPT', 'OPENING', 'COUNT_SURPLUS', 'DISCREPANCY'));
ALTER TABLE lot ADD CONSTRAINT lot_receipt_line_check
    CHECK ((source_type = 'RECEIPT') = (goods_receipt_note_line_id IS NOT NULL));
ALTER TABLE lot ADD CONSTRAINT lot_stock_count_check
    CHECK ((source_type IN ('OPENING', 'COUNT_SURPLUS')) = (stock_count_id IS NOT NULL));
ALTER TABLE lot ADD CONSTRAINT lot_supplier_check
    CHECK (source_type IN ('RECEIPT', 'DISCREPANCY') OR supplier_id IS NULL);

ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_resolution_type_check;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_resolution_type_check
    CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN', 'RELEASED_FROM_AWAITING_RETURN', 'RETURNED_TO_SUPPLIER',
                               'REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE',
                               'APPROVED_LOSS', 'CLOSED_BY_AGREEMENT', 'ACCEPTED_AS_VARIANT',
                               'RECEIVED_AS_OTHER_PRODUCT'));

ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
    CHECK (movement_type IN ('SALE_ISSUE', 'RECEIPT_CONFIRMED', 'EXCESS_ACCEPTED', 'EXCESS_REJECTED', 'RETURN_HOLD',
                             'RETURN_RELEASE', 'SUPPLIER_RETURN', 'TRANSFER_DISPATCH', 'TRANSFER_EXTRA_DISPATCH',
                             'TRANSFER_RECEIPT', 'ADJUSTMENT', 'COUNT_ADJUSTMENT', 'OPENING_BALANCE',
                             'DAMAGED_TO_VARIANT', 'WRONG_ITEM_RECLASSIFIED'));

-- PR 5: the dented-goods variant of a part ("<SKU>-M"), at most one per part.
ALTER TABLE product ADD COLUMN variant_of_product_id BIGINT REFERENCES product (id);
CREATE UNIQUE INDEX uq_product_variant_of ON product (variant_of_product_id) WHERE variant_of_product_id IS NOT NULL;
