-- Report 4, section 3 (Database Design, 25/10): 43 tables -> 50 tables.
--   * System-assigned codes are dropped: the id is the document number (Report 3, 3.3).
--     Only user-entered keys stay unique: sku, supplier_code, username, barcode, store_name, tier_name.
--   * retail_price, wholesale_price and discount_threshold become pricing_policy
--     (BR-10: no per-store wholesale price).
--   * New: customer_tier, customer_tier_change, lot, lot_balance, inventory_period,
--     stock_count, stock_count_line, period_stock_balance, daily_stock_balance.
--   * Moving weighted average cost (product, stock_movement), lot and period on every movement.
-- Constraints across columns and indexes follow the conventions of V1..V6.

-- ---------------------------------------------------------------------------
-- 1. Drop system-assigned codes
-- ---------------------------------------------------------------------------
ALTER TABLE store DROP COLUMN store_code;
ALTER TABLE store ADD CONSTRAINT store_store_name_key UNIQUE (store_name);
ALTER TABLE customer DROP COLUMN customer_code;
ALTER TABLE sales_order DROP COLUMN order_code;
ALTER TABLE payment DROP COLUMN payment_code;
ALTER TABLE goods_receipt_request DROP COLUMN request_code;
ALTER TABLE goods_receipt DROP COLUMN receipt_code;
ALTER TABLE replenishment_request DROP COLUMN request_code;
ALTER TABLE transfer_order DROP COLUMN transfer_code;
ALTER TABLE discrepancy_case DROP COLUMN case_code;
ALTER TABLE adjustment_request DROP COLUMN adjustment_code;

-- ---------------------------------------------------------------------------
-- 2. Pricing: three tables -> pricing_policy
-- ---------------------------------------------------------------------------
-- rule: Rows are never updated except status; a new version supersedes the old one (price history)
-- rule: Value in force at time t = ACTIVE row of the same key with the largest effective_from <= t
CREATE TABLE pricing_policy (
    id BIGSERIAL PRIMARY KEY,
    policy_type VARCHAR(25) NOT NULL CHECK (policy_type IN ('RETAIL_PRICE','MARKUP_RATE','ORDER_VALUE_BAND','DISCOUNT_THRESHOLD','MINIMUM_MARGIN','EXPIRY_WARNING')),
    product_id BIGINT,
    category_id BIGINT,
    unit_price BIGINT CHECK (unit_price > 0),
    markup_rate NUMERIC(5,2) CHECK (markup_rate >= 0),
    rounding_rule VARCHAR(20) CHECK (rounding_rule IN ('NONE','UP_100','UP_500','UP_1000')),
    order_value_from BIGINT CHECK (order_value_from >= 0),
    rate NUMERIC(5,2) CHECK (rate >= 0 AND rate <= 100),
    expiry_warning_days INTEGER CHECK (expiry_warning_days > 0),
    effective_from TIMESTAMP NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','ACTIVE','SUPERSEDED')),
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL,
    CHECK (policy_type <> 'RETAIL_PRICE' OR (product_id IS NOT NULL AND category_id IS NULL AND unit_price IS NOT NULL)),
    CHECK (policy_type <> 'MARKUP_RATE' OR (num_nonnulls(product_id, category_id) = 1 AND markup_rate IS NOT NULL)),
    CHECK (policy_type <> 'ORDER_VALUE_BAND' OR (order_value_from IS NOT NULL AND rate IS NOT NULL)),
    CHECK (policy_type NOT IN ('DISCOUNT_THRESHOLD','MINIMUM_MARGIN') OR rate IS NOT NULL),
    CHECK (policy_type <> 'EXPIRY_WARNING' OR expiry_warning_days IS NOT NULL),
    CHECK (policy_type IN ('RETAIL_PRICE','MARKUP_RATE') OR (product_id IS NULL AND category_id IS NULL)),
    UNIQUE NULLS NOT DISTINCT (policy_type, product_id, category_id, order_value_from, effective_from)
);

-- Retail prices keep their history; wholesale prices per store and discount thresholds
-- move as chain-wide settings only where they map one to one.
INSERT INTO pricing_policy (policy_type, product_id, unit_price, effective_from, status, set_by, set_at)
SELECT 'RETAIL_PRICE', product_id, unit_price, effective_from,
       CASE WHEN effective_from > NOW() THEN 'SCHEDULED'
            WHEN effective_from = MAX(effective_from) FILTER (WHERE effective_from <= NOW()) OVER (PARTITION BY product_id) THEN 'ACTIVE'
            ELSE 'SUPERSEDED' END,
       set_by, set_at
FROM retail_price;

INSERT INTO pricing_policy (policy_type, rate, effective_from, status, set_by, set_at)
SELECT 'DISCOUNT_THRESHOLD', max_discount_rate, effective_from,
       CASE WHEN effective_from > NOW() THEN 'SCHEDULED'
            WHEN effective_from = MAX(effective_from) FILTER (WHERE effective_from <= NOW()) OVER () THEN 'ACTIVE'
            ELSE 'SUPERSEDED' END,
       set_by, set_at
FROM discount_threshold;

DROP TABLE retail_price;
DROP TABLE wholesale_price;
DROP TABLE discount_threshold;

-- ---------------------------------------------------------------------------
-- 3. Customer tiers
-- ---------------------------------------------------------------------------
-- rule: A tier never grants credit eligibility
CREATE TABLE customer_tier (
    id BIGSERIAL PRIMARY KEY,
    tier_name VARCHAR(50) NOT NULL UNIQUE,
    additional_rate NUMERIC(5,2) NOT NULL CHECK (additional_rate >= 0 AND additional_rate < 100),
    effective_from TIMESTAMP NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL
);

-- customer.customer_tier_id is the current tier, set only by Owner in the same transaction as customer_tier_change
ALTER TABLE customer ADD COLUMN customer_tier_id BIGINT;

-- rule: Rows are never updated or deleted; the previous tier is the new tier of the previous change
CREATE TABLE customer_tier_change (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    customer_tier_id BIGINT,
    reason VARCHAR(255),
    changed_by BIGINT NOT NULL,
    changed_at TIMESTAMP NOT NULL
);

-- ---------------------------------------------------------------------------
-- 4. Columns added to existing tables
-- ---------------------------------------------------------------------------
-- product: one initial unit cost per SKU (opening count) and the chain-wide moving weighted average cost
ALTER TABLE product ADD COLUMN initial_unit_cost BIGINT CHECK (initial_unit_cost > 0);
ALTER TABLE product ADD COLUMN moving_avg_cost NUMERIC(18,4) CHECK (moving_avg_cost >= 0);

ALTER TABLE sales_order_version ADD COLUMN suggested_discount_rate NUMERIC(5,2)
    CHECK (suggested_discount_rate >= 0 AND suggested_discount_rate < 100);

-- goods_receipt: created when the goods arrive, before the assigned Staff records the inspection
ALTER TABLE goods_receipt DROP CONSTRAINT goods_receipt_status_check;
UPDATE goods_receipt SET status = 'AWAITING_INSPECTION' WHERE submitted_at IS NULL AND status = 'AWAITING_CONFIRMATION';
ALTER TABLE goods_receipt ADD CONSTRAINT goods_receipt_status_check
    CHECK (status IN ('AWAITING_INSPECTION','AWAITING_CONFIRMATION','AWAITING_RECOUNT','CONFIRMED'));
ALTER TABLE goods_receipt ALTER COLUMN status SET DEFAULT 'AWAITING_INSPECTION';
ALTER TABLE goods_receipt ALTER COLUMN recorded_by DROP NOT NULL;
ALTER TABLE goods_receipt ALTER COLUMN submitted_at DROP NOT NULL;
ALTER TABLE goods_receipt ADD CHECK (status = 'AWAITING_INSPECTION' OR (recorded_by IS NOT NULL AND submitted_at IS NOT NULL));

-- goods_receipt_line: invoice price and the supplier's lot data; one lot per supplier receipt line,
-- so a part delivered with two expiry dates takes two lines
ALTER TABLE goods_receipt_line DROP CONSTRAINT goods_receipt_line_goods_receipt_id_product_id_key;
ALTER TABLE goods_receipt_line ADD COLUMN unit_price BIGINT CHECK (unit_price > 0);
ALTER TABLE goods_receipt_line ADD COLUMN manufacturer_lot_no VARCHAR(50);
ALTER TABLE goods_receipt_line ADD COLUMN manufacture_date DATE;
ALTER TABLE goods_receipt_line ADD COLUMN expiry_date DATE;

ALTER TABLE replenishment_request_line ADD COLUMN proposed_qty INTEGER CHECK (proposed_qty >= 0);
ALTER TABLE replenishment_request_line ADD COLUMN accepted_qty INTEGER CHECK (accepted_qty >= 0);

-- transfer_order: may be created by Owner or the BM (Central Inventory) without a replenishment request
ALTER TABLE transfer_order ALTER COLUMN replenishment_request_id DROP NOT NULL;

-- discrepancy_case: OPEN or CLOSED only
UPDATE discrepancy_case SET status = 'OPEN' WHERE status = 'IN_PROGRESS';
ALTER TABLE discrepancy_case DROP CONSTRAINT discrepancy_case_status_check;
ALTER TABLE discrepancy_case ADD CONSTRAINT discrepancy_case_status_check CHECK (status IN ('OPEN','CLOSED'));

-- ---------------------------------------------------------------------------
-- 5. Inventory periods, lots and stock counts
-- ---------------------------------------------------------------------------
-- rule: One calendar month for the whole chain; closed in month order by Owner and never reopened
CREATE TABLE inventory_period (
    id BIGSERIAL PRIMARY KEY,
    period_month DATE NOT NULL UNIQUE,
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    previous_period_id BIGINT UNIQUE,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_by BIGINT,
    closed_at TIMESTAMP,
    CHECK (period_month = date_trunc('month', period_month)::date),
    CHECK (from_date = period_month AND to_date >= from_date),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- rule: Book quantities are fixed when counting starts; a count with no difference completes without approval
CREATE TABLE stock_count (
    id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    inventory_period_id BIGINT,
    count_type VARCHAR(10) NOT NULL CHECK (count_type IN ('OPENING','YEAR_END','CYCLE')),
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('WHOLE','PRODUCTS','AREA')),
    scope_note VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','COUNTING','AWAITING_APPROVAL','COMPLETED','CANCELLED')),
    created_by BIGINT NOT NULL,
    counting_started_at TIMESTAMP,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    approved_by BIGINT,
    approved_at TIMESTAMP,
    decision_reason VARCHAR(255),
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK ((count_type = 'OPENING') = (inventory_period_id IS NULL)),
    CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);

-- rule: One lot per supplier receipt line (accepted excess is added to the same lot); lots are never merged
-- rule: Used for FEFO/FIFO picking and traceability; it does not set the cost
CREATE TABLE lot (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    source_type VARCHAR(15) NOT NULL CHECK (source_type IN ('RECEIPT','OPENING','COUNT_SURPLUS')),
    goods_receipt_line_id BIGINT UNIQUE,
    stock_count_id BIGINT,
    supplier_id BIGINT,
    warehouse_id BIGINT NOT NULL,
    received_date DATE NOT NULL,
    purchase_unit_price BIGINT NOT NULL CHECK (purchase_unit_price >= 0),
    manufacturer_lot_no VARCHAR(50),
    manufacture_date DATE,
    expiry_date DATE,
    status VARCHAR(10) NOT NULL DEFAULT 'IN_STOCK' CHECK (status IN ('IN_STOCK','DEPLETED','EXPIRED')),
    CHECK ((source_type = 'RECEIPT') = (goods_receipt_line_id IS NOT NULL)),
    CHECK ((source_type = 'RECEIPT') = (stock_count_id IS NULL)),
    CHECK (source_type = 'RECEIPT' OR supplier_id IS NULL)
);

-- rule: Updated in the same transaction as stock_movement; the sum over lots equals stock_balance
CREATE TABLE lot_balance (
    id BIGSERIAL PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (lot_id, warehouse_id, stock_status)
);

-- rule: difference = counted_qty - book_qty is derived, not stored
CREATE TABLE stock_count_line (
    id BIGSERIAL PRIMARY KEY,
    stock_count_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_id BIGINT,
    expiry_date DATE,
    book_qty INTEGER NOT NULL CHECK (book_qty >= 0),
    counted_qty INTEGER CHECK (counted_qty >= 0),
    reason VARCHAR(255),
    recount_requested BOOLEAN NOT NULL DEFAULT FALSE,
    UNIQUE NULLS NOT DISTINCT (stock_count_id, product_id, lot_id, expiry_date)
);

-- rule: Written once when the period is closed (NXT report, S2d-HKD ledger)
CREATE TABLE period_stock_balance (
    id BIGSERIAL PRIMARY KEY,
    inventory_period_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    opening_qty INTEGER NOT NULL,
    opening_value BIGINT NOT NULL,
    in_qty INTEGER NOT NULL CHECK (in_qty >= 0),
    in_value BIGINT NOT NULL,
    out_qty INTEGER NOT NULL CHECK (out_qty >= 0),
    out_value BIGINT NOT NULL,
    closing_qty INTEGER NOT NULL,
    closing_value BIGINT NOT NULL,
    period_avg_unit_cost NUMERIC(18,4),
    UNIQUE (inventory_period_id, warehouse_id, product_id),
    CHECK (closing_qty = opening_qty + in_qty - out_qty),
    CHECK (closing_value = opening_value + in_value - out_value)
);

-- rule: Written by the end-of-day job; used only to look up past stock
CREATE TABLE daily_stock_balance (
    id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    balance_date DATE NOT NULL,
    closing_qty INTEGER NOT NULL,
    UNIQUE (warehouse_id, product_id, balance_date)
);

-- ---------------------------------------------------------------------------
-- 6. Stock ledger and adjustments: lot, cost, period, stock count
-- ---------------------------------------------------------------------------
-- Every existing movement needs a period and a cost. Iteration 1 posts no movements, so these
-- columns are added NOT NULL directly; the migration stops here if a database already has movements.
ALTER TABLE stock_movement ADD COLUMN lot_id BIGINT;
ALTER TABLE stock_movement ADD COLUMN inventory_period_id BIGINT NOT NULL;
ALTER TABLE stock_movement ADD COLUMN unit_cost NUMERIC(18,4) NOT NULL CHECK (unit_cost >= 0);
ALTER TABLE stock_movement ADD COLUMN value BIGINT NOT NULL;
ALTER TABLE stock_movement ADD COLUMN stock_count_id BIGINT;
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_movement_type_check
    CHECK (movement_type IN ('SALE_ISSUE','RECEIPT_CONFIRMED','EXCESS_ACCEPTED','EXCESS_REJECTED','RETURN_HOLD','SUPPLIER_RETURN','TRANSFER_DISPATCH','TRANSFER_EXTRA_DISPATCH','TRANSFER_RECEIPT','ADJUSTMENT','COUNT_ADJUSTMENT','OPENING_BALANCE'));
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_check;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_check
    CHECK (num_nonnulls(sales_order_id, goods_receipt_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id, stock_count_id) = 1);
ALTER TABLE stock_movement ADD CHECK ((movement_type IN ('COUNT_ADJUSTMENT','OPENING_BALANCE')) = (stock_count_id IS NOT NULL));
ALTER TABLE stock_movement DROP CONSTRAINT stock_movement_movement_type_sales_order_id_goods_receipt_i_key;
ALTER TABLE stock_movement ADD CONSTRAINT stock_movement_source_key UNIQUE NULLS NOT DISTINCT
    (movement_type, sales_order_id, goods_receipt_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id, stock_count_id, warehouse_id, product_id, lot_id, stock_status);

-- NULL lot_id = lots deducted FEFO, then FIFO when the request is applied
ALTER TABLE adjustment_stock_line ADD COLUMN lot_id BIGINT;
ALTER TABLE adjustment_stock_line DROP CONSTRAINT adjustment_stock_line_adjustment_request_id_warehouse_id_pr_key;
ALTER TABLE adjustment_stock_line ADD CONSTRAINT adjustment_stock_line_line_key UNIQUE NULLS NOT DISTINCT
    (adjustment_request_id, warehouse_id, product_id, lot_id, stock_status);

-- ---------------------------------------------------------------------------
-- 7. Warehouse tasks: inspection belongs to the goods receipt; counting tasks
-- ---------------------------------------------------------------------------
DROP INDEX ux_warehouse_task_4;
ALTER TABLE warehouse_task DROP CONSTRAINT warehouse_task_check;
ALTER TABLE warehouse_task DROP CONSTRAINT warehouse_task_check1;
ALTER TABLE warehouse_task DROP CONSTRAINT warehouse_task_check2;
ALTER TABLE warehouse_task DROP CONSTRAINT warehouse_task_task_type_check;
ALTER TABLE warehouse_task DROP COLUMN goods_receipt_request_id;
ALTER TABLE warehouse_task ADD COLUMN goods_receipt_id BIGINT;
ALTER TABLE warehouse_task ADD COLUMN stock_count_id BIGINT;
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_task_type_check
    CHECK (task_type IN ('PICKING','INSPECTION','DISPATCH_PREPARATION','COUNTING'));
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_check
    CHECK (num_nonnulls(sales_order_id, goods_receipt_id, transfer_order_id, stock_count_id) = 1);
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_check1 CHECK ((task_type = 'PICKING') = (sales_order_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_check2 CHECK ((task_type = 'INSPECTION') = (goods_receipt_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_check3 CHECK ((task_type = 'DISPATCH_PREPARATION') = (transfer_order_id IS NOT NULL));
ALTER TABLE warehouse_task ADD CONSTRAINT warehouse_task_check4 CHECK ((task_type = 'COUNTING') = (stock_count_id IS NOT NULL));
-- One open task per document; a reassignment cancels the old row and keeps it as history
CREATE UNIQUE INDEX ux_warehouse_task_4 ON warehouse_task (task_type, sales_order_id, goods_receipt_id, transfer_order_id, stock_count_id)
    NULLS NOT DISTINCT WHERE status IN ('ASSIGNED','RESULT_SUBMITTED');

-- ---------------------------------------------------------------------------
-- 8. Foreign keys
-- ---------------------------------------------------------------------------
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_category_id FOREIGN KEY (category_id) REFERENCES part_category(id);
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE customer_tier ADD CONSTRAINT fk_customer_tier_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE customer ADD CONSTRAINT fk_customer_customer_tier_id FOREIGN KEY (customer_tier_id) REFERENCES customer_tier(id);
ALTER TABLE customer_tier_change ADD CONSTRAINT fk_customer_tier_change_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE customer_tier_change ADD CONSTRAINT fk_customer_tier_change_customer_tier_id FOREIGN KEY (customer_tier_id) REFERENCES customer_tier(id);
ALTER TABLE customer_tier_change ADD CONSTRAINT fk_customer_tier_change_changed_by FOREIGN KEY (changed_by) REFERENCES user_account(id);
ALTER TABLE inventory_period ADD CONSTRAINT fk_inventory_period_previous_period_id FOREIGN KEY (previous_period_id) REFERENCES inventory_period(id);
ALTER TABLE inventory_period ADD CONSTRAINT fk_inventory_period_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_approved_by FOREIGN KEY (approved_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_goods_receipt_line_id FOREIGN KEY (goods_receipt_line_id) REFERENCES goods_receipt_line(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_supplier_id FOREIGN KEY (supplier_id) REFERENCES supplier(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE lot_balance ADD CONSTRAINT fk_lot_balance_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE lot_balance ADD CONSTRAINT fk_lot_balance_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE daily_stock_balance ADD CONSTRAINT fk_daily_stock_balance_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE daily_stock_balance ADD CONSTRAINT fk_daily_stock_balance_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);

-- ---------------------------------------------------------------------------
-- 9. Indexes on foreign keys (actor columns *_by excluded, as in V4) and lookups
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS ix_warehouse_task_goods_receipt_request_id;
CREATE INDEX ix_pricing_policy_product_id ON pricing_policy (product_id);
CREATE INDEX ix_pricing_policy_category_id ON pricing_policy (category_id);
CREATE INDEX ix_pricing_policy_1 ON pricing_policy (policy_type, status, effective_from);
CREATE INDEX ix_customer_customer_tier_id ON customer (customer_tier_id);
CREATE INDEX ix_customer_tier_change_customer_id ON customer_tier_change (customer_id);
CREATE INDEX ix_customer_tier_change_customer_tier_id ON customer_tier_change (customer_tier_id);
CREATE INDEX ix_stock_count_warehouse_id ON stock_count (warehouse_id);
CREATE INDEX ix_stock_count_inventory_period_id ON stock_count (inventory_period_id);
CREATE INDEX ix_lot_product_id ON lot (product_id);
CREATE INDEX ix_lot_stock_count_id ON lot (stock_count_id);
CREATE INDEX ix_lot_supplier_id ON lot (supplier_id);
CREATE INDEX ix_lot_warehouse_id ON lot (warehouse_id);
CREATE INDEX ix_lot_1 ON lot (product_id, expiry_date, received_date) WHERE status = 'IN_STOCK';
CREATE INDEX ix_lot_balance_warehouse_id ON lot_balance (warehouse_id);
CREATE INDEX ix_stock_count_line_product_id ON stock_count_line (product_id);
CREATE INDEX ix_stock_count_line_lot_id ON stock_count_line (lot_id);
CREATE INDEX ix_period_stock_balance_warehouse_id ON period_stock_balance (warehouse_id);
CREATE INDEX ix_period_stock_balance_product_id ON period_stock_balance (product_id);
CREATE INDEX ix_daily_stock_balance_product_id ON daily_stock_balance (product_id);
CREATE INDEX ix_stock_movement_lot_id ON stock_movement (lot_id);
CREATE INDEX ix_stock_movement_inventory_period_id ON stock_movement (inventory_period_id);
CREATE INDEX ix_stock_movement_stock_count_id ON stock_movement (stock_count_id);
CREATE INDEX ix_adjustment_stock_line_lot_id ON adjustment_stock_line (lot_id);
CREATE INDEX ix_warehouse_task_goods_receipt_id ON warehouse_task (goods_receipt_id);
CREATE INDEX ix_warehouse_task_stock_count_id ON warehouse_task (stock_count_id);
