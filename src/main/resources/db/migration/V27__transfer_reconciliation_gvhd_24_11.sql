-- GVHD 24/11 (plan C, sai lệch điều chuyển).

-- Phiếu nhận hàng giao sai now waits for the SOURCE store's BM instead of the Owner, and that BM may reject it.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_status_check;
UPDATE discrepancy_resolution_event SET status = 'AWAITING_SOURCE' WHERE status = 'AWAITING_OWNER';
ALTER TABLE discrepancy_resolution_event
    ALTER COLUMN status TYPE VARCHAR(20),
    ADD CONSTRAINT ck_dre_status CHECK (status IN ('AWAITING_SOURCE', 'CONFIRMED', 'REJECTED', 'CANCELLED')),
    ADD COLUMN rejected_by BIGINT REFERENCES user_account(id),
    ADD COLUMN rejected_at TIMESTAMP,
    ADD COLUMN rejection_reason VARCHAR(255),
    ADD CONSTRAINT ck_dre_rejection CHECK (CASE WHEN status = 'REJECTED'
        THEN rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL
        ELSE rejected_by IS NULL AND rejected_at IS NULL AND rejection_reason IS NULL END);

-- WRONG_ITEM_RETURNED: the destination hands wrong items back to the source. Nothing moves (they never left the
-- source's books); the event carries the part they really are. Review 25/11 (R-14): like a phiếu it has a status,
-- as the source's BM confirms it came back or rejects it.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT ck_discrepancy_resolution_event_wrong_item_receipt;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT ck_discrepancy_resolution_event_wrong_item_receipt CHECK (
    (received_product_id IS NULL AND received_expiry_date IS NULL AND status IS NULL)
    OR resolution_type = 'RECEIVED_AS_OTHER_PRODUCT'
    OR (resolution_type = 'WRONG_ITEM_RETURNED' AND received_product_id IS NOT NULL
        AND received_expiry_date IS NULL AND status IS NOT NULL));

ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_resolution_type_check;
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_resolution_type_check
    CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN', 'RELEASED_FROM_AWAITING_RETURN', 'RETURNED_TO_SUPPLIER',
                               'REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE',
                               'APPROVED_LOSS', 'CLOSED_BY_AGREEMENT', 'ACCEPTED_AS_VARIANT',
                               'RECEIVED_AS_OTHER_PRODUCT', 'NO_REPLACEMENT', 'RECEIVER_MISCOUNT',
                               'WRONG_ITEM_RETURNED'));

-- Yêu cầu đối soát: discrepancy_acknowledged_by/_at (V22) now mean "the destination's BM sent the reconciliation
-- request"; the source's BM confirms it here. Closing an order with discrepancies needs both.
ALTER TABLE transfer_order
    ADD COLUMN reconciliation_note VARCHAR(255),
    ADD COLUMN reconciliation_confirmed_by BIGINT REFERENCES user_account(id),
    ADD COLUMN reconciliation_confirmed_at TIMESTAMP,
    ADD CONSTRAINT ck_transfer_reconciliation_pair
        CHECK ((reconciliation_confirmed_by IS NULL) = (reconciliation_confirmed_at IS NULL));

-- Review 25/11 (R-10): orders closed before this step was added count as confirmed by whoever closed them.
UPDATE transfer_order
SET reconciliation_confirmed_by = closed_by,
    reconciliation_confirmed_at = closed_at
WHERE status = 'CLOSED'
  AND discrepancy_acknowledged_at IS NOT NULL
  AND closed_by IS NOT NULL
  AND closed_at IS NOT NULL;
