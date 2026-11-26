-- Plan 25/11: the history of a deposit's expiry date joins the other histories in audit_log (rule of 29/10), one
-- SALES_ORDER_DEPOSIT_EXPIRY_CHANGED row per change with the date before and after as yyyy-MM-dd.
INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
SELECT 'SALES_ORDER', c.sales_order_id, 'SALES_ORDER_DEPOSIT_EXPIRY_CHANGED', c.changed_by, c.changed_at, NULL,
       jsonb_build_object('deposit_expires_on', to_char(c.old_date, 'YYYY-MM-DD')),
       jsonb_build_object('deposit_expires_on', to_char(c.new_date, 'YYYY-MM-DD'))
FROM sales_deposit_expiry_change c
ORDER BY c.changed_at, c.id;

DO $$
BEGIN
    IF (SELECT count(*) FROM audit_log WHERE action = 'SALES_ORDER_DEPOSIT_EXPIRY_CHANGED')
       <> (SELECT count(*) FROM sales_deposit_expiry_change) THEN
        RAISE EXCEPTION 'V29: the expiry history was not copied in full';
    END IF;
END $$;

DROP TABLE sales_deposit_expiry_change;
