-- Stock consistency check: every row returned is a mismatch, so an empty result means the books agree.
-- stock_balance (per status) = sum(lot_balance) = net stock_movement, per store, product and status.
--
--   psql -h localhost -U root -d motoflow -f scripts/seed/check_stock_consistency.sql

WITH sb AS (
    SELECT store_id, product_id, s.status, s.qty
    FROM stock_balance
    CROSS JOIN LATERAL (VALUES ('AVAILABLE', available_qty), ('AWAITING_INSPECTION', awaiting_inspection_qty),
                               ('WRONG_ITEM', wrong_item_qty), ('DAMAGED', damaged_qty),
                               ('AWAITING_SUPPLIER_RETURN', awaiting_supplier_return_qty),
                               ('IN_TRANSIT', in_transit_qty)) AS s(status, qty)
),
lb AS (
    SELECT b.store_id, l.product_id, b.stock_status AS status, sum(b.quantity) AS qty
    FROM lot_balance b JOIN lot l ON l.id = b.lot_id
    GROUP BY 1, 2, 3
),
mv AS (
    SELECT store_id, product_id, stock_status AS status, sum(quantity_change) AS qty
    FROM stock_movement
    GROUP BY 1, 2, 3
)
SELECT store_id, product_id, status,
       coalesce(sb.qty, 0) AS stock_balance, coalesce(lb.qty, 0) AS lot_balance, coalesce(mv.qty, 0) AS movements
FROM sb FULL JOIN lb USING (store_id, product_id, status) FULL JOIN mv USING (store_id, product_id, status)
WHERE coalesce(sb.qty, 0) <> coalesce(lb.qty, 0) OR coalesce(lb.qty, 0) <> coalesce(mv.qty, 0)
ORDER BY 1, 2, 3;
