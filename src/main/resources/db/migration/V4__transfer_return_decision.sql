-- UC-76 Step 3_Return to source (review R-06): the source may open a return only after a coordinator decided it. The
-- decision is a RECEIVED_BACK_AT_SOURCE event that stays pending (confirmed_at NULL, no GRN) until the return receipt
-- is confirmed and settles it. REPLACEMENT_RECEIVED and LATE_DELIVERY_RECEIVED keep requiring a GRN.
-- V1: the unnamed table CHECK on discrepancy_resolution_event, named discrepancy_resolution_event_check by PostgreSQL.
ALTER TABLE discrepancy_resolution_event DROP CONSTRAINT discrepancy_resolution_event_check;

ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT discrepancy_resolution_event_grn_when_confirmed
    CHECK (resolution_type NOT IN ('REPLACEMENT_RECEIVED', 'LATE_DELIVERY_RECEIVED', 'RECEIVED_BACK_AT_SOURCE')
           OR goods_receipt_note_id IS NOT NULL
           OR (resolution_type = 'RECEIVED_BACK_AT_SOURCE' AND confirmed_at IS NULL));
