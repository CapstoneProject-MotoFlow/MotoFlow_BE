-- Backfill a missing discrepancy case for the DC-0069 example recorded in the plan.
-- This script intentionally posts no stock movements. Reconcile the existing ledger before changing any quantity.
BEGIN;

SELECT id FROM transfer_order WHERE id = 69 AND status = 'RECONCILING' FOR UPDATE;

WITH target AS (
    SELECT t.id AS order_id, line.product_id, r.confirmed_at,
           GREATEST(line.document_qty - line.passed_qty - line.damaged_qty, 0) AS short_qty,
           line.wrong_item_qty, line.damaged_qty
    FROM transfer_order t
    JOIN goods_receipt_note r ON r.transfer_order_id = t.id
    JOIN goods_receipt_note_line line ON line.goods_receipt_note_id = r.id
    WHERE t.id = 69 AND t.status = 'RECONCILING'
      AND r.id = 196 AND r.receipt_type = 'TRANSFER_IN' AND r.status = 'CONFIRMED'
      AND r.confirmed_at IS NOT NULL
      AND line.document_qty = 15 AND line.delivered_qty = 15
      AND line.passed_qty = 6 AND line.damaged_qty = 9
      AND line.wrong_item_qty = 0 AND line.excess_qty = 0
)
INSERT INTO discrepancy_case
    (source_type, transfer_order_id, product_id, short_qty, wrong_item_qty, damaged_qty,
     replacement_limit_qty, status, opened_at)
SELECT 'TRANSFER', order_id, product_id, short_qty, wrong_item_qty, damaged_qty, 0, 'OPEN', confirmed_at
FROM target
ON CONFLICT DO NOTHING
RETURNING id, transfer_order_id, product_id, short_qty, damaged_qty;

COMMIT;
