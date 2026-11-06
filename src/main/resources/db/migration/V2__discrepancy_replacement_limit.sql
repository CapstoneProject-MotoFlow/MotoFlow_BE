-- UC-46 step 3, BR-27, BR-67 (review of PR #26, R-01 and R-02).
-- The replacement agreed with the supplier is entered apart from the return, so a part may be replaced without going
-- back. It never covers rejected excess, and never more than the purchase order still lacked once the receipt was
-- stocked: replacement_limit_qty, set when the receipt is confirmed. NULL on rows saved before this migration, which
-- keep short + wrong + damaged as their limit.
ALTER TABLE discrepancy_case ADD COLUMN replacement_limit_qty INTEGER CHECK (replacement_limit_qty >= 0);

-- A case opened on a replacement delivery owes no replacement of its own: what that delivery did not bring stays owed
-- on the original case. Its short_qty and replacement_required_qty were already 0.
UPDATE discrepancy_case c SET replacement_limit_qty = 0
WHERE c.source_type = 'SUPPLIER'
  AND EXISTS (SELECT 1 FROM discrepancy_resolution_event e
              WHERE e.goods_receipt_note_id = c.goods_receipt_note_id
                AND e.resolution_type = 'REPLACEMENT_RECEIVED');

-- V1: CHECK (replacement_required_qty <= short_qty + return_required_qty), the fourth unnamed table CHECK.
ALTER TABLE discrepancy_case DROP CONSTRAINT discrepancy_case_check3;

ALTER TABLE discrepancy_case ADD CONSTRAINT discrepancy_case_replacement_limit_check
    CHECK (replacement_limit_qty <= short_qty + wrong_item_qty + damaged_qty);
ALTER TABLE discrepancy_case ADD CONSTRAINT discrepancy_case_replacement_required_check
    CHECK (replacement_required_qty <= COALESCE(replacement_limit_qty, short_qty + wrong_item_qty + damaged_qty));
