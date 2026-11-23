-- PM 23/11 (plan "sai lệch điều chuyển", bản 2): new rules for what a transfer's receiving store found.

-- Phiếu nhận hàng giao sai: the receiving store's BM records what part the wrong items really are; nothing moves until
-- the Owner confirms it. CANCELLED only when the Owner finds the receiver miscounted the line.
ALTER TABLE discrepancy_resolution_event
    ADD COLUMN received_product_id BIGINT REFERENCES product(id),
    ADD COLUMN received_expiry_date DATE,
    ADD COLUMN status VARCHAR(15) CHECK (status IN ('AWAITING_OWNER', 'CONFIRMED', 'CANCELLED')),
    ADD CONSTRAINT ck_discrepancy_resolution_event_wrong_item_receipt CHECK (
        (received_product_id IS NULL AND received_expiry_date IS NULL AND status IS NULL)
        OR resolution_type = 'RECEIVED_AS_OTHER_PRODUCT');

-- NO_REPLACEMENT: the receiving store does not need the short part any more; it goes back to the source's available
-- stock when the order closes. RECEIVER_MISCOUNT: the Owner found the receiving store counted the line wrong.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_resolution_type_check;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_resolution_type_check
    CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN', 'RELEASED_FROM_AWAITING_RETURN', 'RETURNED_TO_SUPPLIER',
                               'REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE',
                               'APPROVED_LOSS', 'CLOSED_BY_AGREEMENT', 'ACCEPTED_AS_VARIANT',
                               'RECEIVED_AS_OTHER_PRODUCT', 'NO_REPLACEMENT', 'RECEIVER_MISCOUNT'));

-- A "late delivery" decision stays pending, with no GRN, until the next receipt brings the goods.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_grn_when_confirmed;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_grn_when_confirmed
    CHECK (resolution_type NOT IN ('REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE')
           OR goods_receipt_note_id IS NOT NULL
           OR (resolution_type IN ('RECEIVED_BACK_AT_SOURCE', 'LATE_DELIVERY_RECEIVED') AND confirmed_at IS NULL));

-- TRANSFER_WRONG_ITEM: the Owner-confirmed wrong item leaves the source for the destination.
-- TRANSFER_SHORT_RETURNED: on closing, the short part not to be replaced leaves transit back to the source.
-- TRANSFER_RECEIPT_CORRECTED: the receiver miscounted, the destination gets what was dispatched.
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
    CHECK (movement_type IN ('SALE_ISSUE', 'RECEIPT_CONFIRMED', 'EXCESS_ACCEPTED', 'EXCESS_REJECTED', 'RETURN_HOLD',
                             'RETURN_RELEASE', 'SUPPLIER_RETURN', 'TRANSFER_DISPATCH', 'TRANSFER_EXTRA_DISPATCH',
                             'TRANSFER_RECEIPT', 'ADJUSTMENT', 'COUNT_ADJUSTMENT', 'OPENING_BALANCE',
                             'DAMAGED_TO_VARIANT', 'WRONG_ITEM_RECLASSIFIED', 'TRANSFER_WRONG_ITEM',
                             'TRANSFER_SHORT_RETURNED', 'TRANSFER_RECEIPT_CORRECTED'));
