-- GVHD 24/11 (plan A): "Đơn chưa đáp ứng đủ" lists waiting orders only, so an order must remember it waited for stock
-- after it was released (it keeps its number) or cancelled. Plus the expected date the goods arrive and when the
-- store was last told the order is ready.
ALTER TABLE sales_order
    ADD COLUMN waited_for_stock BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN expected_ready_date DATE,
    ADD COLUMN ready_notified_at TIMESTAMP,
    ADD CONSTRAINT ck_sales_order_expected_ready CHECK (expected_ready_date IS NULL OR waited_for_stock);

-- Backfill. A waiting order is one that:
--   * still waits;
--   * was released in full or in part (a SALES_ORDER_VERSIONED row with cause RELEASE);
--   * was split from another order and is not the part the customer declined: the rest of a new order that waits
--     (UC-53), or the part a partial release sold (it came out of a waiting order);
--   * had a deposit (only a waiting order takes one, PM 21/11).
-- A waiting order that was cancelled with no deposit, no release and no split leaves no trace and stays false.
UPDATE sales_order o SET waited_for_stock = true
WHERE o.status = 'AWAITING_STOCK'
   OR o.deposit_expires_on IS NOT NULL
   OR o.deposit_forfeited_at IS NOT NULL
   OR EXISTS (SELECT 1 FROM audit_log a
              WHERE a.entity_type = 'SALES_ORDER' AND a.entity_id = o.id
                AND a.action = 'SALES_ORDER_VERSIONED' AND a.new_data ->> 'cause' = 'RELEASE')
   OR (EXISTS (SELECT 1 FROM audit_log a
               WHERE a.entity_type = 'SALES_ORDER' AND a.entity_id = o.id
                 AND a.action = 'SALES_ORDER_CREATED' AND a.new_data ? 'split_from_order_id')
       AND o.cancellation_reason IS DISTINCT FROM 'Khách không chờ hàng')
   OR EXISTS (SELECT 1 FROM sales_deposit_application d WHERE d.waiting_order_id = o.id)
   OR EXISTS (SELECT 1 FROM payment p WHERE p.sales_order_id = o.id AND p.payment_type = 'DEPOSIT');

CREATE INDEX idx_sales_order_waiting ON sales_order (store_id, created_at) WHERE waited_for_stock;
