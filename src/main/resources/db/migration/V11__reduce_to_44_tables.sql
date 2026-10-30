-- V11: 51 -> 44 tables (R4 29/10).
-- 1. warehouse merged into store (store_id was UNIQUE: one warehouse per store).
-- 2. user_permission -> two flags on user_account.
-- 3. permission_history, credit_eligibility_event, sales_order_version -> audit_log.
-- 4. unmet_demand and daily_stock_balance dropped.
-- 5. stock_count_line gets its natural key.

-- 1. warehouse -> store --------------------------------------------------------------------
ALTER TABLE store ADD COLUMN is_central BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE store s SET is_central = w.is_central FROM warehouse w WHERE w.store_id = s.id;
CREATE UNIQUE INDEX ux_store_central ON store (is_central) WHERE is_central;

ALTER TABLE adjustment_stock_line DROP CONSTRAINT fk_adjustment_stock_line_warehouse_id;
ALTER TABLE daily_stock_balance   DROP CONSTRAINT fk_daily_stock_balance_warehouse_id;
ALTER TABLE goods_receipt_request DROP CONSTRAINT fk_goods_receipt_request_warehouse_id;
ALTER TABLE lot                   DROP CONSTRAINT fk_lot_warehouse_id;
ALTER TABLE lot_balance           DROP CONSTRAINT fk_lot_balance_warehouse_id;
ALTER TABLE period_stock_balance  DROP CONSTRAINT fk_period_stock_balance_warehouse_id;
ALTER TABLE stock_balance         DROP CONSTRAINT fk_stock_balance_warehouse_id;
ALTER TABLE stock_count           DROP CONSTRAINT fk_stock_count_warehouse_id;
ALTER TABLE stock_count_request   DROP CONSTRAINT fk_stock_count_request_warehouse_id;
ALTER TABLE stock_movement        DROP CONSTRAINT fk_stock_movement_warehouse_id;
ALTER TABLE transfer_order        DROP CONSTRAINT fk_transfer_order_source_warehouse_id;
ALTER TABLE transfer_order        DROP CONSTRAINT fk_transfer_order_dest_warehouse_id;

-- Map warehouse ids to store ids in two passes (first negate every column, then map) so
-- unique keys and the source <> dest check never see a temporary clash while ids are swapped.
DO $$
DECLARE
    c RECORD;
BEGIN
    FOR c IN SELECT * FROM (VALUES
        ('adjustment_stock_line','warehouse_id'), ('goods_receipt_request','warehouse_id'),
        ('lot','warehouse_id'), ('lot_balance','warehouse_id'), ('period_stock_balance','warehouse_id'),
        ('stock_balance','warehouse_id'), ('stock_count','warehouse_id'), ('stock_count_request','warehouse_id'),
        ('stock_movement','warehouse_id'), ('transfer_order','source_warehouse_id'),
        ('transfer_order','dest_warehouse_id')) AS t(tbl, col)
    LOOP
        EXECUTE format('UPDATE %I SET %I = -%I', c.tbl, c.col, c.col);
    END LOOP;
    FOR c IN SELECT * FROM (VALUES
        ('adjustment_stock_line','warehouse_id'), ('goods_receipt_request','warehouse_id'),
        ('lot','warehouse_id'), ('lot_balance','warehouse_id'), ('period_stock_balance','warehouse_id'),
        ('stock_balance','warehouse_id'), ('stock_count','warehouse_id'), ('stock_count_request','warehouse_id'),
        ('stock_movement','warehouse_id'), ('transfer_order','source_warehouse_id'),
        ('transfer_order','dest_warehouse_id')) AS t(tbl, col)
    LOOP
        EXECUTE format('UPDATE %I t SET %I = w.store_id FROM warehouse w WHERE t.%I = -w.id', c.tbl, c.col, c.col);
    END LOOP;
END $$;

ALTER TABLE adjustment_stock_line RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE goods_receipt_request RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE lot                   RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE lot_balance           RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE period_stock_balance  RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE stock_balance         RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE stock_count           RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE stock_count_request   RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE stock_movement        RENAME COLUMN warehouse_id TO store_id;
ALTER TABLE transfer_order        RENAME COLUMN source_warehouse_id TO source_store_id;
ALTER TABLE transfer_order        RENAME COLUMN dest_warehouse_id TO dest_store_id;

ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE lot                   ADD CONSTRAINT fk_lot_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE lot_balance           ADD CONSTRAINT fk_lot_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE period_stock_balance  ADD CONSTRAINT fk_period_stock_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_balance         ADD CONSTRAINT fk_stock_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_count           ADD CONSTRAINT fk_stock_count_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_count_request   ADD CONSTRAINT fk_stock_count_request_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_movement        ADD CONSTRAINT fk_stock_movement_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE transfer_order        ADD CONSTRAINT fk_transfer_order_source_store_id FOREIGN KEY (source_store_id) REFERENCES store(id);
ALTER TABLE transfer_order        ADD CONSTRAINT fk_transfer_order_dest_store_id FOREIGN KEY (dest_store_id) REFERENCES store(id);

ALTER INDEX ix_adjustment_stock_line_warehouse_id RENAME TO ix_adjustment_stock_line_store_id;
ALTER INDEX ix_goods_receipt_request_warehouse_id RENAME TO ix_goods_receipt_request_store_id;
ALTER INDEX ix_lot_warehouse_id                   RENAME TO ix_lot_store_id;
ALTER INDEX ix_lot_balance_warehouse_id           RENAME TO ix_lot_balance_store_id;
ALTER INDEX ix_period_stock_balance_warehouse_id  RENAME TO ix_period_stock_balance_store_id;
ALTER INDEX ix_stock_count_warehouse_id           RENAME TO ix_stock_count_store_id;
ALTER INDEX ix_stock_count_request_warehouse_id   RENAME TO ix_stock_count_request_store_id;
ALTER INDEX ix_transfer_order_source_warehouse_id RENAME TO ix_transfer_order_source_store_id;
ALTER INDEX ix_transfer_order_dest_warehouse_id   RENAME TO ix_transfer_order_dest_store_id;

-- 4. (daily_stock_balance references warehouse, so drop it before warehouse)
DROP TABLE daily_stock_balance;
DROP TABLE warehouse;

-- 2. user_permission -> user_account flags --------------------------------------------------
ALTER TABLE user_account
    ADD COLUMN can_edit_catalog BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN can_edit_retail_price BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE user_account u SET
    can_edit_catalog      = EXISTS (SELECT 1 FROM user_permission p WHERE p.user_id = u.id AND p.permission_code = 'CATALOG_EDIT'),
    can_edit_retail_price = EXISTS (SELECT 1 FROM user_permission p WHERE p.user_id = u.id AND p.permission_code = 'RETAIL_PRICE_EDIT');
DROP TABLE user_permission;

-- 3. history tables -> audit_log ------------------------------------------------------------
INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
SELECT 'USER_ACCOUNT', user_id, change_type, changed_by, changed_at, reason,
       jsonb_strip_nulls(jsonb_build_object('permission_code', permission_code, 'value', old_value)),
       jsonb_strip_nulls(jsonb_build_object('permission_code', permission_code, 'value', new_value))
FROM permission_history;
DROP TABLE permission_history;

INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
SELECT 'CUSTOMER', customer_id, 'CREDIT_' || event_type, decided_by, decided_at, reason,
       NULL, jsonb_build_object('credit_event', event_type)
FROM credit_eligibility_event;
DROP TABLE credit_eligibility_event;

-- sales_order_version: the latest version becomes the order's own data; every version
-- (header + lines) is kept as one audit_log row.
ALTER TABLE sales_order
    ADD COLUMN customer_id BIGINT,
    ADD COLUMN customer_name_snapshot VARCHAR(150),
    ADD COLUMN customer_phone_snapshot VARCHAR(20),
    ADD COLUMN customer_address_snapshot VARCHAR(255),
    ADD COLUMN price_type VARCHAR(10),
    ADD COLUMN discount_rate NUMERIC(5,2) NOT NULL DEFAULT 0,
    ADD COLUMN confirmed_by BIGINT,
    ADD COLUMN confirmed_at TIMESTAMP,
    ADD COLUMN credit_decision VARCHAR(10),
    ADD COLUMN approved_credit_amount BIGINT,
    ADD COLUMN credit_decided_by BIGINT,
    ADD COLUMN credit_decided_at TIMESTAMP;

INSERT INTO audit_log (entity_type, entity_id, action, actor_id, occurred_at, reason, old_data, new_data)
SELECT 'SALES_ORDER', v.sales_order_id, 'VERSION_' || v.version_no, v.created_by, v.created_at, v.change_reason,
       NULL,
       to_jsonb(v) - 'id' || jsonb_build_object('lines',
           COALESCE((SELECT jsonb_agg(to_jsonb(l) - 'id' - 'sales_order_version_id' ORDER BY l.line_no)
                     FROM sales_order_line l WHERE l.sales_order_version_id = v.id), '[]'::jsonb))
FROM sales_order_version v;

UPDATE sales_order o SET
    customer_id = v.customer_id, customer_name_snapshot = v.customer_name_snapshot,
    customer_phone_snapshot = v.customer_phone_snapshot, customer_address_snapshot = v.customer_address_snapshot,
    price_type = v.price_type, discount_rate = v.discount_rate,
    confirmed_by = v.confirmed_by, confirmed_at = v.confirmed_at,
    credit_decision = v.credit_decision, approved_credit_amount = v.approved_credit_amount,
    credit_decided_by = v.credit_decided_by, credit_decided_at = v.credit_decided_at
FROM (SELECT DISTINCT ON (sales_order_id) * FROM sales_order_version
      ORDER BY sales_order_id, version_no DESC) v
WHERE v.sales_order_id = o.id;
UPDATE sales_order SET price_type = 'RETAIL' WHERE price_type IS NULL;

ALTER TABLE sales_order
    ALTER COLUMN price_type SET NOT NULL,
    ADD CONSTRAINT sales_order_price_type_check CHECK (price_type IN ('RETAIL','WHOLESALE')),
    ADD CONSTRAINT sales_order_discount_rate_check CHECK (discount_rate >= 0 AND discount_rate < 100),
    ADD CONSTRAINT sales_order_credit_decision_check CHECK (credit_decision IN ('APPROVED','REJECTED')),
    ADD CONSTRAINT sales_order_approved_credit_amount_check CHECK (approved_credit_amount > 0),
    ADD CONSTRAINT sales_order_credit_amount_check CHECK ((credit_decision = 'APPROVED') = (approved_credit_amount IS NOT NULL)),
    ADD CONSTRAINT sales_order_credit_decider_check CHECK ((credit_decision IS NULL) = (credit_decided_by IS NULL)),
    ADD CONSTRAINT fk_sales_order_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id),
    ADD CONSTRAINT fk_sales_order_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id),
    ADD CONSTRAINT fk_sales_order_credit_decided_by FOREIGN KEY (credit_decided_by) REFERENCES user_account(id);
CREATE INDEX ix_sales_order_customer_id ON sales_order (customer_id);
CREATE INDEX ix_sales_order_confirmed_by ON sales_order (confirmed_by);
CREATE INDEX ix_sales_order_credit_decided_by ON sales_order (credit_decided_by);

-- Keep only the lines of each order's latest version, then point them at the order.
ALTER TABLE sales_order_line ADD COLUMN sales_order_id BIGINT;
UPDATE sales_order_line l SET sales_order_id = v.sales_order_id
FROM sales_order_version v WHERE v.id = l.sales_order_version_id;
DELETE FROM sales_order_line l
WHERE l.sales_order_version_id <> (SELECT v2.id FROM sales_order_version v2
                                   WHERE v2.sales_order_id = l.sales_order_id
                                   ORDER BY v2.version_no DESC LIMIT 1);
ALTER TABLE sales_order_line DROP COLUMN sales_order_version_id;
ALTER TABLE sales_order_line
    ALTER COLUMN sales_order_id SET NOT NULL,
    ADD CONSTRAINT fk_sales_order_line_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id),
    ADD CONSTRAINT sales_order_line_sales_order_id_line_no_key UNIQUE (sales_order_id, line_no),
    ADD CONSTRAINT sales_order_line_sales_order_id_product_id_key UNIQUE (sales_order_id, product_id);
DROP TABLE sales_order_version;

-- 4. unmet_demand ----------------------------------------------------------------------------
DROP TABLE unmet_demand;

-- 5. stock_count_line natural key -------------------------------------------------------------
ALTER TABLE stock_count_line
    ADD CONSTRAINT stock_count_line_line_key UNIQUE NULLS NOT DISTINCT (stock_count_id, product_id, lot_id);
