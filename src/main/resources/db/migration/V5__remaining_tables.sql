-- Remaining tables of the logical design (MotoFlow_Logical_3NF/schema_postgres.sql);
-- store, warehouse and the account tables come from V1..V4. Keep both in sync.
-- Requires PostgreSQL 15+ (UNIQUE NULLS NOT DISTINCT).

-- rule: Cycles (A -> B -> A) are rejected by the application
CREATE TABLE part_category (
    id BIGSERIAL PRIMARY KEY,
    category_name VARCHAR(100) NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE','BICYCLE','E_MOTORCYCLE','E_BICYCLE')),
    parent_id BIGINT,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (vehicle_type, parent_id, category_name)
);

CREATE TABLE vehicle_model (
    id BIGSERIAL PRIMARY KEY,
    manufacturer VARCHAR(100) NOT NULL,
    model_name VARCHAR(100) NOT NULL,
    vehicle_type VARCHAR(20) NOT NULL CHECK (vehicle_type IN ('MOTORCYCLE','BICYCLE','E_MOTORCYCLE','E_BICYCLE')),
    year_from SMALLINT,
    year_to SMALLINT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE NULLS NOT DISTINCT (manufacturer, model_name, year_from, year_to),
    CHECK (year_from IS NULL OR year_to IS NULL OR year_from <= year_to)
);

CREATE TABLE product (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(50) NOT NULL UNIQUE,
    part_name VARCHAR(200) NOT NULL,
    category_id BIGINT NOT NULL,
    brand VARCHAR(100),
    country_of_origin VARCHAR(100),
    base_unit VARCHAR(20) NOT NULL,
    specifications TEXT,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISCONTINUED')),
    discontinued_at TIMESTAMP,
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE product_identifier (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    identifier_type VARCHAR(10) NOT NULL CHECK (identifier_type IN ('OEM','BARCODE')),
    identifier_value VARCHAR(100) NOT NULL,
    UNIQUE (product_id, identifier_type, identifier_value)
);

CREATE TABLE product_image (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    storage_key VARCHAR(500) NOT NULL,
    sort_order SMALLINT NOT NULL DEFAULT 0,
    is_primary BOOLEAN NOT NULL DEFAULT FALSE,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL
);

-- rule: Deactivating a vehicle model keeps its links (UC-25)
CREATE TABLE product_vehicle_model (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    vehicle_model_id BIGINT NOT NULL,
    UNIQUE (product_id, vehicle_model_id)
);

-- rule: Price in force at time t = row with the largest effective_from <= t (one active price, BR-09)
CREATE TABLE retail_price (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    unit_price BIGINT NOT NULL CHECK (unit_price > 0),
    effective_from TIMESTAMP NOT NULL,
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL,
    UNIQUE (product_id, effective_from)
);

-- rule: Price in force: same rule as retail_price, per (product, store)
CREATE TABLE wholesale_price (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    unit_price BIGINT NOT NULL CHECK (unit_price > 0),
    effective_from TIMESTAMP NOT NULL,
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL,
    UNIQUE (product_id, store_id, effective_from)
);

CREATE TABLE discount_threshold (
    id BIGSERIAL PRIMARY KEY,
    max_discount_rate NUMERIC(5,2) NOT NULL CHECK (max_discount_rate > 0 AND max_discount_rate <= 100),
    effective_from TIMESTAMP NOT NULL UNIQUE,
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL
);

CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    customer_code VARCHAR(20) NOT NULL UNIQUE,
    customer_name VARCHAR(150) NOT NULL,
    phone VARCHAR(20),
    address VARCHAR(255),
    tax_code VARCHAR(20),
    credit_status VARCHAR(20) NOT NULL DEFAULT 'NOT_GRANTED' CHECK (credit_status IN ('NOT_GRANTED','ELIGIBLE','REVOKED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- rule: Rows are never updated or deleted
CREATE TABLE credit_eligibility_event (
    id BIGSERIAL PRIMARY KEY,
    customer_id BIGINT NOT NULL,
    event_type VARCHAR(10) NOT NULL CHECK (event_type IN ('GRANTED','REVOKED')),
    reason VARCHAR(255),
    decided_by BIGINT NOT NULL,
    decided_at TIMESTAMP NOT NULL
);

CREATE TABLE supplier (
    id BIGSERIAL PRIMARY KEY,
    supplier_code VARCHAR(20) NOT NULL UNIQUE,
    supplier_name VARCHAR(150) NOT NULL,
    contact_name VARCHAR(100),
    phone VARCHAR(20),
    email VARCHAR(100),
    address VARCHAR(255),
    tax_code VARCHAR(20),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- rule: Current version = MAX(version_no) in sales_order_version (not stored)
CREATE TABLE sales_order (
    id BIGSERIAL PRIMARY KEY,
    order_code VARCHAR(30) NOT NULL UNIQUE,
    store_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING_PICKING' CHECK (status IN ('AWAITING_PICKING','PICKED','CONFIRMED','COMPLETED','CANCELLED')),
    completion_type VARCHAR(10) CHECK (completion_type IN ('PAID','CREDIT')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_by BIGINT,
    completed_at TIMESTAMP,
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK ((status = 'COMPLETED') = (completion_type IS NOT NULL)),
    CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);

-- rule: A version is frozen once a later version exists; its price and credit decision stay unchanged (BR-18, BR-36)
-- rule: Completing on credit also re-checks customer.credit_status at completion time (BR-44)
CREATE TABLE sales_order_version (
    id BIGSERIAL PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    version_no SMALLINT NOT NULL,
    customer_id BIGINT,
    customer_name_snapshot VARCHAR(150),
    customer_phone_snapshot VARCHAR(20),
    customer_address_snapshot VARCHAR(255),
    price_type VARCHAR(10) NOT NULL CHECK (price_type IN ('RETAIL','WHOLESALE')),
    discount_rate NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (discount_rate >= 0 AND discount_rate < 100),
    change_reason VARCHAR(255),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    credit_decision VARCHAR(10) CHECK (credit_decision IN ('APPROVED','REJECTED')),
    approved_credit_amount BIGINT CHECK (approved_credit_amount > 0),
    credit_decided_by BIGINT,
    credit_decided_at TIMESTAMP,
    UNIQUE (sales_order_id, version_no),
    CHECK ((credit_decision = 'APPROVED') = (approved_credit_amount IS NOT NULL)),
    CHECK ((credit_decision IS NULL) = (credit_decided_by IS NULL))
);

CREATE TABLE sales_order_line (
    id BIGSERIAL PRIMARY KEY,
    sales_order_version_id BIGINT NOT NULL,
    line_no SMALLINT NOT NULL,
    product_id BIGINT NOT NULL,
    requested_qty INTEGER NOT NULL CHECK (requested_qty > 0),
    picked_qty INTEGER CHECK (picked_qty >= 0),
    delivered_qty INTEGER CHECK (delivered_qty >= 0),
    reference_price BIGINT NOT NULL CHECK (reference_price > 0),
    actual_unit_price BIGINT NOT NULL CHECK (actual_unit_price > 0),
    UNIQUE (sales_order_version_id, line_no),
    UNIQUE (sales_order_version_id, product_id),
    CHECK (delivered_qty IS NULL OR delivered_qty <= requested_qty)
);

CREATE TABLE unmet_demand (
    id BIGSERIAL PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    unmet_qty INTEGER NOT NULL CHECK (unmet_qty > 0),
    status VARCHAR(10) NOT NULL DEFAULT 'WAITING' CHECK (status IN ('WAITING','CLOSED')),
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    fulfilled_by_order_id BIGINT,
    closed_at TIMESTAMP,
    close_reason VARCHAR(255),
    UNIQUE (sales_order_id, product_id),
    CHECK (fulfilled_by_order_id IS NULL OR fulfilled_by_order_id <> sales_order_id),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

-- rule: CREDIT_SALE: original_amount must not exceed approved_credit_amount of the order's current version (BR-42), checked by the application
-- rule: No due date (Entity Descriptions, BR-53)
CREATE TABLE receivable (
    id BIGSERIAL PRIMARY KEY,
    origin_type VARCHAR(20) NOT NULL CHECK (origin_type IN ('CREDIT_SALE','OPENING_BALANCE')),
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    sales_order_id BIGINT UNIQUE,
    original_amount BIGINT NOT NULL CHECK (original_amount > 0),
    note VARCHAR(500),
    recognized_by BIGINT NOT NULL,
    recognized_at TIMESTAMP NOT NULL,
    settled_at TIMESTAMP,
    CHECK ((origin_type = 'CREDIT_SALE') = (sales_order_id IS NOT NULL))
);

CREATE TABLE payment (
    id BIGSERIAL PRIMARY KEY,
    payment_code VARCHAR(30) NOT NULL UNIQUE,
    payment_type VARCHAR(20) NOT NULL CHECK (payment_type IN ('ORDER_PAYMENT','REFUND','DEBT_PAYMENT')),
    sales_order_id BIGINT,
    receivable_id BIGINT,
    amount BIGINT NOT NULL CHECK (amount > 0),
    tendered_amount BIGINT CHECK (tendered_amount > 0),
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CASH','BANK_TRANSFER')),
    transaction_reference VARCHAR(100),
    status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED' CHECK (status IN ('UNVERIFIED','VERIFIED','REJECTED')),
    recorded_by BIGINT NOT NULL,
    recorded_at TIMESTAMP NOT NULL,
    verified_by BIGINT,
    verified_at TIMESTAMP,
    rejection_reason VARCHAR(255),
    CHECK ((payment_type IN ('ORDER_PAYMENT','REFUND') AND sales_order_id IS NOT NULL AND receivable_id IS NULL) OR (payment_type = 'DEBT_PAYMENT' AND receivable_id IS NOT NULL AND sales_order_id IS NULL)),
    CHECK (tendered_amount IS NULL OR tendered_amount >= amount),
    CHECK (status <> 'REJECTED' OR rejection_reason IS NOT NULL)
);

-- rule: Editable or cancellable only before the first delivery is recorded (BR-21); edits logged in audit_log (UC-43)
CREATE TABLE goods_receipt_request (
    id BIGSERIAL PRIMARY KEY,
    request_code VARCHAR(30) NOT NULL UNIQUE,
    supplier_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    replenishment_request_id BIGINT,
    expected_arrival DATE,
    note VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING_DELIVERY' CHECK (status IN ('AWAITING_DELIVERY','RECEIVING','FULLY_RECEIVED','CANCELLED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    CHECK (status <> 'CANCELLED' OR cancellation_reason IS NOT NULL)
);

-- rule: ordered_qty keeps the figure agreed with the supplier and is never changed after the first delivery (BR-21)
-- rule: Accepted excess (UC-46) is derived, not stored: SUM(goods_receipt_line.excess_qty) with excess_decision = 'ACCEPTED' over the request's receipts
CREATE TABLE goods_receipt_request_line (
    id BIGSERIAL PRIMARY KEY,
    goods_receipt_request_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    ordered_qty INTEGER NOT NULL CHECK (ordered_qty > 0),
    purchase_price BIGINT NOT NULL CHECK (purchase_price > 0),
    UNIQUE (goods_receipt_request_id, product_id)
);

-- rule: Receiving warehouse is derived: request's warehouse, transfer destination, or transfer source for RETURN_TO_SOURCE
CREATE TABLE goods_receipt (
    id BIGSERIAL PRIMARY KEY,
    receipt_code VARCHAR(30) NOT NULL UNIQUE,
    receipt_type VARCHAR(20) NOT NULL CHECK (receipt_type IN ('SUPPLIER_DELIVERY','TRANSFER_IN','RETURN_TO_SOURCE')),
    goods_receipt_request_id BIGINT,
    transfer_order_id BIGINT,
    delivery_document_no VARCHAR(50),
    arrived_at TIMESTAMP NOT NULL,
    status VARCHAR(25) NOT NULL DEFAULT 'AWAITING_CONFIRMATION' CHECK (status IN ('AWAITING_CONFIRMATION','AWAITING_RECOUNT','CONFIRMED')),
    recorded_by BIGINT NOT NULL,
    submitted_at TIMESTAMP NOT NULL,
    recount_reason VARCHAR(255),
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    excess_decided_by BIGINT,
    excess_decided_at TIMESTAMP,
    CHECK ((receipt_type = 'SUPPLIER_DELIVERY') = (goods_receipt_request_id IS NOT NULL)),
    CHECK ((receipt_type = 'SUPPLIER_DELIVERY') = (transfer_order_id IS NULL)),
    CHECK (receipt_type <> 'SUPPLIER_DELIVERY' OR delivery_document_no IS NOT NULL),
    UNIQUE (goods_receipt_request_id, delivery_document_no),
    CHECK (confirmed_by IS NULL OR confirmed_by <> recorded_by)
);

-- rule: short quantity = GREATEST(document_qty - delivered_qty, 0) is derived, not stored (BR-24)
CREATE TABLE goods_receipt_line (
    id BIGSERIAL PRIMARY KEY,
    goods_receipt_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    document_qty INTEGER NOT NULL CHECK (document_qty >= 0),
    delivered_qty INTEGER NOT NULL CHECK (delivered_qty >= 0),
    passed_qty INTEGER NOT NULL CHECK (passed_qty >= 0),
    wrong_item_qty INTEGER NOT NULL DEFAULT 0 CHECK (wrong_item_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    excess_qty INTEGER NOT NULL DEFAULT 0 CHECK (excess_qty >= 0),
    excess_decision VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK (excess_decision IN ('NOT_APPLICABLE','AWAITING_OWNER','ACCEPTED','REJECTED')),
    UNIQUE (goods_receipt_id, product_id),
    CHECK (delivered_qty = passed_qty + wrong_item_qty + damaged_qty),
    CHECK (excess_qty <= passed_qty)
);

CREATE TABLE replenishment_request (
    id BIGSERIAL PRIMARY KEY,
    request_code VARCHAR(30) NOT NULL UNIQUE,
    requesting_store_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AWAITING_COORDINATION' CHECK (status IN ('AWAITING_COORDINATION','PARTIAL_SUPPLY_PROPOSED','IN_PROGRESS','CLOSED')),
    partial_supply_decision VARCHAR(10) CHECK (partial_supply_decision IN ('ACCEPTED','DECLINED')),
    partial_decided_by BIGINT,
    partial_decided_at TIMESTAMP,
    note VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMP
);

-- rule: Proposed, accepted and fulfilled quantities are derived from transfer_order_line
CREATE TABLE replenishment_request_line (
    id BIGSERIAL PRIMARY KEY,
    replenishment_request_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    requested_qty INTEGER NOT NULL CHECK (requested_qty > 0),
    to_purchase_qty INTEGER NOT NULL DEFAULT 0 CHECK (to_purchase_qty >= 0),
    UNIQUE (replenishment_request_id, product_id),
    CHECK (to_purchase_qty <= requested_qty)
);

-- rule: Closed only when every dispatched quantity has a final result (BR-63)
CREATE TABLE transfer_order (
    id BIGSERIAL PRIMARY KEY,
    transfer_code VARCHAR(30) NOT NULL UNIQUE,
    replenishment_request_id BIGINT NOT NULL,
    source_warehouse_id BIGINT NOT NULL,
    dest_warehouse_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING_DISPATCH' CHECK (status IN ('PROPOSED','AWAITING_DISPATCH','IN_TRANSIT','RECONCILING','CLOSED','CANCELLED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    dispatched_by BIGINT,
    dispatched_at TIMESTAMP,
    extra_dispatch_confirmed_by BIGINT,
    extra_dispatch_confirmed_at TIMESTAMP,
    closed_by BIGINT,
    closed_at TIMESTAMP,
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    CHECK (source_warehouse_id <> dest_warehouse_id)
);

-- rule: Received, returned, lost and unresolved quantities are derived (BR-60)
CREATE TABLE transfer_order_line (
    id BIGSERIAL PRIMARY KEY,
    transfer_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    planned_qty INTEGER NOT NULL CHECK (planned_qty > 0),
    prepared_qty INTEGER CHECK (prepared_qty >= 0),
    dispatched_qty INTEGER CHECK (dispatched_qty >= 0),
    extra_dispatched_qty INTEGER NOT NULL DEFAULT 0 CHECK (extra_dispatched_qty >= 0),
    makeup_for_case_id BIGINT,
    UNIQUE (transfer_order_id, product_id)
);

CREATE TABLE discrepancy_case (
    id BIGSERIAL PRIMARY KEY,
    case_code VARCHAR(30) NOT NULL UNIQUE,
    source_type VARCHAR(10) NOT NULL CHECK (source_type IN ('SUPPLIER','TRANSFER')),
    goods_receipt_id BIGINT,
    transfer_order_id BIGINT,
    product_id BIGINT NOT NULL,
    short_qty INTEGER NOT NULL DEFAULT 0 CHECK (short_qty >= 0),
    wrong_item_qty INTEGER NOT NULL DEFAULT 0 CHECK (wrong_item_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    rejected_excess_qty INTEGER NOT NULL DEFAULT 0 CHECK (rejected_excess_qty >= 0),
    return_required_qty INTEGER NOT NULL DEFAULT 0 CHECK (return_required_qty >= 0),
    replacement_required_qty INTEGER NOT NULL DEFAULT 0 CHECK (replacement_required_qty >= 0),
    status VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED')),
    opened_at TIMESTAMP NOT NULL,
    closure_type VARCHAR(25) CHECK (closure_type IN ('ALL_SETTLED','CLOSED_BY_AGREEMENT')),
    closure_reason VARCHAR(255),
    closed_by BIGINT,
    closed_at TIMESTAMP,
    CHECK ((source_type = 'SUPPLIER') = (goods_receipt_id IS NOT NULL)),
    CHECK ((source_type = 'TRANSFER') = (transfer_order_id IS NOT NULL)),
    UNIQUE NULLS NOT DISTINCT (goods_receipt_id, transfer_order_id, product_id),
    CHECK (return_required_qty <= wrong_item_qty + damaged_qty + rejected_excess_qty),
    CHECK (replacement_required_qty <= short_qty + return_required_qty),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE discrepancy_resolution_event (
    id BIGSERIAL PRIMARY KEY,
    discrepancy_case_id BIGINT NOT NULL,
    resolution_type VARCHAR(30) NOT NULL CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN','RETURNED_TO_SUPPLIER','REPLACEMENT_RECEIVED','LATE_DELIVERY_RECEIVED','RECEIVED_BACK_AT_SOURCE','APPROVED_LOSS','CLOSED_BY_AGREEMENT')),
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    goods_receipt_id BIGINT,
    adjustment_request_id BIGINT,
    reason VARCHAR(255),
    recorded_by BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    CHECK (resolution_type NOT IN ('REPLACEMENT_RECEIVED','LATE_DELIVERY_RECEIVED','RECEIVED_BACK_AT_SOURCE') OR goods_receipt_id IS NOT NULL),
    CHECK (resolution_type <> 'APPROVED_LOSS' OR adjustment_request_id IS NOT NULL)
);

-- rule: Quantities change only in the same transaction as the stock_movement rows that cause them (BR-29)
CREATE TABLE stock_balance (
    id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    available_qty INTEGER NOT NULL DEFAULT 0 CHECK (available_qty >= 0),
    awaiting_inspection_qty INTEGER NOT NULL DEFAULT 0 CHECK (awaiting_inspection_qty >= 0),
    wrong_item_qty INTEGER NOT NULL DEFAULT 0 CHECK (wrong_item_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    awaiting_supplier_return_qty INTEGER NOT NULL DEFAULT 0 CHECK (awaiting_supplier_return_qty >= 0),
    in_transit_qty INTEGER NOT NULL DEFAULT 0 CHECK (in_transit_qty >= 0),
    storage_area VARCHAR(50),
    bin_location VARCHAR(50),
    low_stock_threshold INTEGER CHECK (low_stock_threshold >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (warehouse_id, product_id)
);

-- rule: Rows are never updated or deleted
CREATE TABLE stock_movement (
    id BIGSERIAL PRIMARY KEY,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity_change INTEGER NOT NULL CHECK (quantity_change <> 0),
    movement_type VARCHAR(30) NOT NULL CHECK (movement_type IN ('SALE_ISSUE','RECEIPT_CONFIRMED','EXCESS_ACCEPTED','EXCESS_REJECTED','RETURN_HOLD','SUPPLIER_RETURN','TRANSFER_DISPATCH','TRANSFER_EXTRA_DISPATCH','TRANSFER_RECEIPT','ADJUSTMENT')),
    sales_order_id BIGINT,
    goods_receipt_id BIGINT,
    transfer_order_id BIGINT,
    discrepancy_resolution_event_id BIGINT,
    adjustment_request_id BIGINT,
    reason VARCHAR(255),
    performed_by BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CHECK (num_nonnulls(sales_order_id, goods_receipt_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id) = 1),
    UNIQUE NULLS NOT DISTINCT (movement_type, sales_order_id, goods_receipt_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id, warehouse_id, product_id, stock_status)
);

CREATE TABLE adjustment_request (
    id BIGSERIAL PRIMARY KEY,
    adjustment_code VARCHAR(30) NOT NULL UNIQUE,
    adjustment_type VARCHAR(25) NOT NULL CHECK (adjustment_type IN ('STOCK_LOSS','DOCUMENT_CORRECTION')),
    store_id BIGINT NOT NULL,
    sales_order_id BIGINT,
    payment_id BIGINT,
    goods_receipt_id BIGINT,
    transfer_order_id BIGINT,
    receivable_id BIGINT,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','PENDING','APPROVED','APPLIED','REJECTED','CANCELLED','WITHDRAWN')),
    requested_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    submitted_at TIMESTAMP,
    decided_by BIGINT,
    decided_at TIMESTAMP,
    decision_reason VARCHAR(255),
    applied_at TIMESTAMP,
    CHECK (num_nonnulls(sales_order_id, payment_id, goods_receipt_id, transfer_order_id, receivable_id) <= 1),
    CHECK (adjustment_type <> 'DOCUMENT_CORRECTION' OR num_nonnulls(sales_order_id, payment_id, goods_receipt_id, transfer_order_id, receivable_id) = 1)
);

CREATE TABLE adjustment_stock_line (
    id BIGSERIAL PRIMARY KEY,
    adjustment_request_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity_change INTEGER NOT NULL CHECK (quantity_change <> 0),
    UNIQUE (adjustment_request_id, warehouse_id, product_id, stock_status)
);

CREATE TABLE adjustment_amount_line (
    id BIGSERIAL PRIMARY KEY,
    adjustment_request_id BIGINT NOT NULL,
    payment_id BIGINT,
    receivable_id BIGINT,
    amount_change BIGINT NOT NULL CHECK (amount_change <> 0),
    CHECK (num_nonnulls(payment_id, receivable_id) = 1)
);

CREATE TABLE warehouse_task (
    id BIGSERIAL PRIMARY KEY,
    task_type VARCHAR(25) NOT NULL CHECK (task_type IN ('PICKING','INSPECTION','DISPATCH_PREPARATION')),
    sales_order_id BIGINT,
    goods_receipt_request_id BIGINT,
    transfer_order_id BIGINT,
    assigned_to BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED','RESULT_SUBMITTED','DONE','CANCELLED')),
    result_submitted_at TIMESTAMP,
    CHECK (num_nonnulls(sales_order_id, goods_receipt_request_id, transfer_order_id) = 1),
    CHECK ((task_type = 'PICKING') = (sales_order_id IS NOT NULL)),
    CHECK (task_type <> 'DISPATCH_PREPARATION' OR transfer_order_id IS NOT NULL)
);

CREATE TABLE notification (
    id BIGSERIAL PRIMARY KEY,
    recipient_id BIGINT NOT NULL,
    notification_type VARCHAR(50) NOT NULL,
    message VARCHAR(500) NOT NULL,
    ref_type VARCHAR(30),
    ref_id BIGINT,
    is_read BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    read_at TIMESTAMP
);

-- rule: Access is checked against the owning record on every download (NFR-S05)
CREATE TABLE attachment (
    id BIGSERIAL PRIMARY KEY,
    owner_type VARCHAR(40) NOT NULL CHECK (owner_type IN ('GOODS_RECEIPT','PAYMENT','RECEIVABLE','DISCREPANCY_CASE','DISCREPANCY_RESOLUTION_EVENT','ADJUSTMENT_REQUEST')),
    owner_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL
);

-- Foreign keys
ALTER TABLE part_category ADD CONSTRAINT fk_part_category_parent_id FOREIGN KEY (parent_id) REFERENCES part_category(id);
ALTER TABLE product ADD CONSTRAINT fk_product_category_id FOREIGN KEY (category_id) REFERENCES part_category(id);
ALTER TABLE product ADD CONSTRAINT fk_product_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE product_identifier ADD CONSTRAINT fk_product_identifier_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_image ADD CONSTRAINT fk_product_image_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_image ADD CONSTRAINT fk_product_image_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES user_account(id);
ALTER TABLE product_vehicle_model ADD CONSTRAINT fk_product_vehicle_model_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_vehicle_model ADD CONSTRAINT fk_product_vehicle_model_vehicle_model_id FOREIGN KEY (vehicle_model_id) REFERENCES vehicle_model(id);
ALTER TABLE retail_price ADD CONSTRAINT fk_retail_price_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE retail_price ADD CONSTRAINT fk_retail_price_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE wholesale_price ADD CONSTRAINT fk_wholesale_price_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE wholesale_price ADD CONSTRAINT fk_wholesale_price_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE wholesale_price ADD CONSTRAINT fk_wholesale_price_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE discount_threshold ADD CONSTRAINT fk_discount_threshold_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE customer ADD CONSTRAINT fk_customer_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE credit_eligibility_event ADD CONSTRAINT fk_credit_eligibility_event_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE credit_eligibility_event ADD CONSTRAINT fk_credit_eligibility_event_decided_by FOREIGN KEY (decided_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_completed_by FOREIGN KEY (completed_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE sales_order_version ADD CONSTRAINT fk_sales_order_version_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE sales_order_version ADD CONSTRAINT fk_sales_order_version_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE sales_order_version ADD CONSTRAINT fk_sales_order_version_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE sales_order_version ADD CONSTRAINT fk_sales_order_version_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE sales_order_version ADD CONSTRAINT fk_sales_order_version_credit_decided_by FOREIGN KEY (credit_decided_by) REFERENCES user_account(id);
ALTER TABLE sales_order_line ADD CONSTRAINT fk_sales_order_line_sales_order_version_id FOREIGN KEY (sales_order_version_id) REFERENCES sales_order_version(id);
ALTER TABLE sales_order_line ADD CONSTRAINT fk_sales_order_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE unmet_demand ADD CONSTRAINT fk_unmet_demand_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE unmet_demand ADD CONSTRAINT fk_unmet_demand_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE unmet_demand ADD CONSTRAINT fk_unmet_demand_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE unmet_demand ADD CONSTRAINT fk_unmet_demand_fulfilled_by_order_id FOREIGN KEY (fulfilled_by_order_id) REFERENCES sales_order(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_recognized_by FOREIGN KEY (recognized_by) REFERENCES user_account(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_verified_by FOREIGN KEY (verified_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_supplier_id FOREIGN KEY (supplier_id) REFERENCES supplier(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_request ADD CONSTRAINT fk_goods_receipt_request_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_request_line ADD CONSTRAINT fk_goods_receipt_request_line_goods_receipt_request_id FOREIGN KEY (goods_receipt_request_id) REFERENCES goods_receipt_request(id);
ALTER TABLE goods_receipt_request_line ADD CONSTRAINT fk_goods_receipt_request_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE goods_receipt ADD CONSTRAINT fk_goods_receipt_goods_receipt_request_id FOREIGN KEY (goods_receipt_request_id) REFERENCES goods_receipt_request(id);
ALTER TABLE goods_receipt ADD CONSTRAINT fk_goods_receipt_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE goods_receipt ADD CONSTRAINT fk_goods_receipt_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt ADD CONSTRAINT fk_goods_receipt_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt ADD CONSTRAINT fk_goods_receipt_excess_decided_by FOREIGN KEY (excess_decided_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_line ADD CONSTRAINT fk_goods_receipt_line_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE goods_receipt_line ADD CONSTRAINT fk_goods_receipt_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE replenishment_request ADD CONSTRAINT fk_replenishment_request_requesting_store_id FOREIGN KEY (requesting_store_id) REFERENCES store(id);
ALTER TABLE replenishment_request ADD CONSTRAINT fk_replenishment_request_partial_decided_by FOREIGN KEY (partial_decided_by) REFERENCES user_account(id);
ALTER TABLE replenishment_request ADD CONSTRAINT fk_replenishment_request_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE replenishment_request_line ADD CONSTRAINT fk_replenishment_request_line_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE replenishment_request_line ADD CONSTRAINT fk_replenishment_request_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_source_warehouse_id FOREIGN KEY (source_warehouse_id) REFERENCES warehouse(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_dest_warehouse_id FOREIGN KEY (dest_warehouse_id) REFERENCES warehouse(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_dispatched_by FOREIGN KEY (dispatched_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_extra_dispatch_confirmed_by FOREIGN KEY (extra_dispatch_confirmed_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_makeup_for_case_id FOREIGN KEY (makeup_for_case_id) REFERENCES discrepancy_case(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_discrepancy_case_id FOREIGN KEY (discrepancy_case_id) REFERENCES discrepancy_case(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE stock_balance ADD CONSTRAINT fk_stock_balance_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE stock_balance ADD CONSTRAINT fk_stock_balance_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_discrepancy_resolution_event_id FOREIGN KEY (discrepancy_resolution_event_id) REFERENCES discrepancy_resolution_event(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_performed_by FOREIGN KEY (performed_by) REFERENCES user_account(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_goods_receipt_id FOREIGN KEY (goods_receipt_id) REFERENCES goods_receipt(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_requested_by FOREIGN KEY (requested_by) REFERENCES user_account(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_decided_by FOREIGN KEY (decided_by) REFERENCES user_account(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_warehouse_id FOREIGN KEY (warehouse_id) REFERENCES warehouse(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_goods_receipt_request_id FOREIGN KEY (goods_receipt_request_id) REFERENCES goods_receipt_request(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_assigned_to FOREIGN KEY (assigned_to) REFERENCES user_account(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_account(id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_recipient_id FOREIGN KEY (recipient_id) REFERENCES user_account(id);
ALTER TABLE attachment ADD CONSTRAINT fk_attachment_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES user_account(id);

-- Indexes
CREATE UNIQUE INDEX ux_product_identifier_2 ON product_identifier (identifier_value) WHERE identifier_type = 'BARCODE';
CREATE UNIQUE INDEX ux_product_image_1 ON product_image (product_id) WHERE is_primary = TRUE;
CREATE INDEX ix_customer_1 ON customer (phone);
CREATE INDEX ix_sales_order_4 ON sales_order (store_id, status);
CREATE INDEX ix_stock_movement_3 ON stock_movement (warehouse_id, product_id, occurred_at);
CREATE UNIQUE INDEX ux_warehouse_task_4 ON warehouse_task (task_type, sales_order_id, goods_receipt_request_id, transfer_order_id) NULLS NOT DISTINCT WHERE status IN ('ASSIGNED','RESULT_SUBMITTED');
CREATE INDEX ix_warehouse_task_5 ON warehouse_task (assigned_to, status);
CREATE INDEX ix_notification_1 ON notification (recipient_id, is_read, created_at);
CREATE INDEX ix_attachment_1 ON attachment (owner_type, owner_id);

-- Foreign-key indexes (FKs not already leading a UNIQUE or INDEX)
CREATE INDEX ix_part_category_parent_id ON part_category (parent_id);
CREATE INDEX ix_product_category_id ON product (category_id);
CREATE INDEX ix_product_image_product_id ON product_image (product_id);
CREATE INDEX ix_product_vehicle_model_vehicle_model_id ON product_vehicle_model (vehicle_model_id);
CREATE INDEX ix_wholesale_price_store_id ON wholesale_price (store_id);
CREATE INDEX ix_credit_eligibility_event_customer_id ON credit_eligibility_event (customer_id);
CREATE INDEX ix_sales_order_version_customer_id ON sales_order_version (customer_id);
CREATE INDEX ix_sales_order_line_product_id ON sales_order_line (product_id);
CREATE INDEX ix_unmet_demand_product_id ON unmet_demand (product_id);
CREATE INDEX ix_unmet_demand_fulfilled_by_order_id ON unmet_demand (fulfilled_by_order_id);
CREATE INDEX ix_receivable_customer_id ON receivable (customer_id);
CREATE INDEX ix_receivable_store_id ON receivable (store_id);
CREATE INDEX ix_payment_sales_order_id ON payment (sales_order_id);
CREATE INDEX ix_payment_receivable_id ON payment (receivable_id);
CREATE INDEX ix_goods_receipt_request_supplier_id ON goods_receipt_request (supplier_id);
CREATE INDEX ix_goods_receipt_request_warehouse_id ON goods_receipt_request (warehouse_id);
CREATE INDEX ix_goods_receipt_request_replenishment_request_id ON goods_receipt_request (replenishment_request_id);
CREATE INDEX ix_goods_receipt_request_line_product_id ON goods_receipt_request_line (product_id);
CREATE INDEX ix_goods_receipt_transfer_order_id ON goods_receipt (transfer_order_id);
CREATE INDEX ix_goods_receipt_line_product_id ON goods_receipt_line (product_id);
CREATE INDEX ix_replenishment_request_requesting_store_id ON replenishment_request (requesting_store_id);
CREATE INDEX ix_replenishment_request_line_product_id ON replenishment_request_line (product_id);
CREATE INDEX ix_transfer_order_replenishment_request_id ON transfer_order (replenishment_request_id);
CREATE INDEX ix_transfer_order_source_warehouse_id ON transfer_order (source_warehouse_id);
CREATE INDEX ix_transfer_order_dest_warehouse_id ON transfer_order (dest_warehouse_id);
CREATE INDEX ix_transfer_order_line_product_id ON transfer_order_line (product_id);
CREATE INDEX ix_transfer_order_line_makeup_for_case_id ON transfer_order_line (makeup_for_case_id);
CREATE INDEX ix_discrepancy_case_transfer_order_id ON discrepancy_case (transfer_order_id);
CREATE INDEX ix_discrepancy_case_product_id ON discrepancy_case (product_id);
CREATE INDEX ix_discrepancy_resolution_event_discrepancy_case_id ON discrepancy_resolution_event (discrepancy_case_id);
CREATE INDEX ix_discrepancy_resolution_event_goods_receipt_id ON discrepancy_resolution_event (goods_receipt_id);
CREATE INDEX ix_discrepancy_resolution_event_adjustment_request_id ON discrepancy_resolution_event (adjustment_request_id);
CREATE INDEX ix_stock_balance_product_id ON stock_balance (product_id);
CREATE INDEX ix_stock_movement_product_id ON stock_movement (product_id);
CREATE INDEX ix_stock_movement_sales_order_id ON stock_movement (sales_order_id);
CREATE INDEX ix_stock_movement_goods_receipt_id ON stock_movement (goods_receipt_id);
CREATE INDEX ix_stock_movement_transfer_order_id ON stock_movement (transfer_order_id);
CREATE INDEX ix_stock_movement_discrepancy_resolution_event_id ON stock_movement (discrepancy_resolution_event_id);
CREATE INDEX ix_stock_movement_adjustment_request_id ON stock_movement (adjustment_request_id);
CREATE INDEX ix_adjustment_request_store_id ON adjustment_request (store_id);
CREATE INDEX ix_adjustment_request_sales_order_id ON adjustment_request (sales_order_id);
CREATE INDEX ix_adjustment_request_payment_id ON adjustment_request (payment_id);
CREATE INDEX ix_adjustment_request_goods_receipt_id ON adjustment_request (goods_receipt_id);
CREATE INDEX ix_adjustment_request_transfer_order_id ON adjustment_request (transfer_order_id);
CREATE INDEX ix_adjustment_request_receivable_id ON adjustment_request (receivable_id);
CREATE INDEX ix_adjustment_stock_line_warehouse_id ON adjustment_stock_line (warehouse_id);
CREATE INDEX ix_adjustment_stock_line_product_id ON adjustment_stock_line (product_id);
CREATE INDEX ix_adjustment_amount_line_adjustment_request_id ON adjustment_amount_line (adjustment_request_id);
CREATE INDEX ix_adjustment_amount_line_payment_id ON adjustment_amount_line (payment_id);
CREATE INDEX ix_adjustment_amount_line_receivable_id ON adjustment_amount_line (receivable_id);
CREATE INDEX ix_warehouse_task_sales_order_id ON warehouse_task (sales_order_id);
CREATE INDEX ix_warehouse_task_goods_receipt_request_id ON warehouse_task (goods_receipt_request_id);
CREATE INDEX ix_warehouse_task_transfer_order_id ON warehouse_task (transfer_order_id);
