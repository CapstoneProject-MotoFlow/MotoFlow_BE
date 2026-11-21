-- Góp ý UI 21/11 (items 18, 21): the return agreed with the supplier is kept per kind of goods, so the store can send
-- back the damaged goods and keep the wrong items (or the other way round) and Owner can still take in what is kept.
-- return_required_qty stays the total: rejected excess + wrong items + damaged goods to return.
ALTER TABLE discrepancy_case
    ADD COLUMN wrong_return_required_qty INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN damaged_return_required_qty INTEGER NOT NULL DEFAULT 0;

-- Until now the return took the rejected excess first, then the wrong items, then the damaged goods.
UPDATE discrepancy_case
   SET wrong_return_required_qty = LEAST(GREATEST(return_required_qty - rejected_excess_qty, 0), wrong_item_qty),
       damaged_return_required_qty = GREATEST(return_required_qty - rejected_excess_qty, 0)
           - LEAST(GREATEST(return_required_qty - rejected_excess_qty, 0), wrong_item_qty);

ALTER TABLE discrepancy_case
    ADD CONSTRAINT ck_discrepancy_case_return_by_kind CHECK (
        wrong_return_required_qty BETWEEN 0 AND wrong_item_qty
        AND damaged_return_required_qty BETWEEN 0 AND damaged_qty
        AND return_required_qty = rejected_excess_qty + wrong_return_required_qty + damaged_return_required_qty);

-- Which goods a return to the supplier handed back; null on returns recorded before (read in the old order).
ALTER TABLE discrepancy_resolution_event
    ADD COLUMN goods_kind VARCHAR(15)
        CHECK (goods_kind IN ('REJECTED_EXCESS', 'WRONG_ITEM', 'DAMAGED')),
    ADD CONSTRAINT ck_discrepancy_resolution_event_goods_kind
        CHECK (goods_kind IS NULL OR resolution_type = 'RETURNED_TO_SUPPLIER');
