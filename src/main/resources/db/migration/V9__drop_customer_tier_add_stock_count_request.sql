-- V9: decisions of 26/10/2026 (Report 3 / Report 4 section 3)
--   * Customer tiers and suggested wholesale discounts by order-value band are dropped.
--   * A Branch Manager's stock count needs an approved stock count request;
--     counts the Owner creates (opening, year-end, cycle) need none.

-- 1. Drop customer tiers
DROP TABLE customer_tier_change;
ALTER TABLE customer DROP COLUMN customer_tier_id;
DROP TABLE customer_tier;

-- 2. Drop order-value discount bands and the suggested discount
DELETE FROM pricing_policy WHERE policy_type = 'ORDER_VALUE_BAND';
-- dropping the column also drops the ORDER_VALUE_BAND check and the old version key
ALTER TABLE pricing_policy DROP COLUMN order_value_from;
DO $$
DECLARE c text;
BEGIN
    SELECT conname INTO c FROM pg_constraint
     WHERE conrelid = 'pricing_policy'::regclass AND contype = 'c'
       AND pg_get_constraintdef(oid) LIKE '%ORDER_VALUE_BAND%';
    IF c IS NOT NULL THEN
        EXECUTE format('ALTER TABLE pricing_policy DROP CONSTRAINT %I', c);
    END IF;
END $$;
ALTER TABLE pricing_policy ADD CONSTRAINT pricing_policy_policy_type_check
    CHECK (policy_type IN ('RETAIL_PRICE','MARKUP_RATE','DISCOUNT_THRESHOLD','MINIMUM_MARGIN','EXPIRY_WARNING'));
-- rule: one version per setting and effective date
ALTER TABLE pricing_policy ADD CONSTRAINT ux_pricing_policy_version
    UNIQUE NULLS NOT DISTINCT (policy_type, product_id, category_id, effective_from);

ALTER TABLE sales_order_version DROP COLUMN suggested_discount_rate;

-- 3. Stock count request
-- rule: Sent by a Branch Manager for the warehouse of their own store; decided by the Owner
-- rule: An approved request creates exactly one stock count
CREATE TABLE stock_count_request (
    id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('WHOLE','PRODUCTS','AREA')),
    scope_note VARCHAR(255),
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    decided_by BIGINT,
    decided_at TIMESTAMP,
    decision_reason VARCHAR(255),
    cancelled_at TIMESTAMP,
    CHECK (status NOT IN ('APPROVED','REJECTED') OR (decided_by IS NOT NULL AND decided_at IS NOT NULL)),
    CHECK (status <> 'REJECTED' OR decision_reason IS NOT NULL),
    CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL)
);

-- rule: Products to count, only when the scope is PRODUCTS
CREATE TABLE stock_count_request_product (
    id BIGSERIAL PRIMARY KEY,
    stock_count_request_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    UNIQUE (stock_count_request_id, product_id)
);

-- rule: Only a cycle count can come from a request; Owner-created counts have none
ALTER TABLE stock_count ADD COLUMN stock_count_request_id BIGINT UNIQUE;
ALTER TABLE stock_count ADD CHECK (stock_count_request_id IS NULL OR count_type = 'CYCLE');

ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_requested_by FOREIGN KEY (requested_by) REFERENCES user_account(id);
ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_decided_by FOREIGN KEY (decided_by) REFERENCES user_account(id);
ALTER TABLE stock_count_request_product ADD CONSTRAINT fk_stock_count_request_product_stock_count_request_id FOREIGN KEY (stock_count_request_id) REFERENCES stock_count_request(id);
ALTER TABLE stock_count_request_product ADD CONSTRAINT fk_stock_count_request_product_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_stock_count_request_id FOREIGN KEY (stock_count_request_id) REFERENCES stock_count_request(id);

CREATE INDEX ix_stock_count_request_warehouse_id ON stock_count_request (warehouse_id);
CREATE INDEX ix_stock_count_request_1 ON stock_count_request (status, requested_at);
CREATE INDEX ix_stock_count_request_product_product_id ON stock_count_request_product (product_id);
