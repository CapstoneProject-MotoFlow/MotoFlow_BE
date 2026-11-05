-- MotoFlow database, R4 section 3 (44 tables). Rebuilt 03/11/2026 from V1..V13 plus the 03/11 changes;
-- an existing database must be dropped and recreated before this runs.
-- Requires PostgreSQL 15+ (UNIQUE NULLS NOT DISTINCT).

CREATE SEQUENCE supplier_code_seq;

CREATE TABLE store (
    id BIGSERIAL PRIMARY KEY,
    store_name VARCHAR(150) NOT NULL UNIQUE,
    address VARCHAR(255) NOT NULL,
    phone VARCHAR(20),
    is_central BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED' CHECK (status IN ('PLANNED','ACTIVE','INACTIVE')),
    copied_from_store_id BIGINT,
    owner_manager_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE user_account (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name VARCHAR(100) NOT NULL,
    phone VARCHAR(20),
    email VARCHAR(100),
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','OWNER','BRANCH_MANAGER','STAFF')),
    store_id BIGINT,
    can_edit_catalog BOOLEAN NOT NULL DEFAULT FALSE,
    can_edit_retail_price BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','LOCKED')),
    failed_login_count SMALLINT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMP,
    created_by BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK ((role IN ('BRANCH_MANAGER','STAFF')) = (store_id IS NOT NULL))
);

CREATE TABLE auth_session (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    refresh_token_hash VARCHAR(64) NOT NULL UNIQUE,
    previous_refresh_token_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    last_refreshed_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    revoke_reason VARCHAR(30) CHECK (revoke_reason IN ('LOGOUT','PASSWORD_CHANGED','PASSWORD_RESET','ACCOUNT_LOCKED','REFRESH_TOKEN_REUSED')),
    user_agent VARCHAR(255),
    ip_address VARCHAR(45)
);

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
    CHECK (year_from IS NULL OR year_to IS NULL OR year_from <= year_to),
    UNIQUE NULLS NOT DISTINCT (manufacturer, model_name, year_from, year_to)
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
    initial_unit_cost BIGINT CHECK (initial_unit_cost > 0),
    moving_avg_cost NUMERIC(18,4) CHECK (moving_avg_cost >= 0),
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

CREATE TABLE product_vehicle_model (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    vehicle_model_id BIGINT NOT NULL,
    UNIQUE (product_id, vehicle_model_id)
);

CREATE TABLE pricing_policy (
    id BIGSERIAL PRIMARY KEY,
    policy_type VARCHAR(25) NOT NULL CHECK (policy_type IN ('RETAIL_PRICE','MARKUP_RATE','DISCOUNT_THRESHOLD','MINIMUM_MARGIN','EXPIRY_WARNING')),
    product_id BIGINT,
    category_id BIGINT,
    unit_price BIGINT CHECK (unit_price > 0),
    markup_rate NUMERIC(5,2) CHECK (markup_rate >= 0),
    rounding_rule VARCHAR(20) CHECK (rounding_rule IN ('NONE','UP_100','UP_500','UP_1000')),
    rate NUMERIC(5,2) CHECK (rate >= 0 AND rate <= 100),
    expiry_warning_days INTEGER CHECK (expiry_warning_days > 0),
    effective_from TIMESTAMP NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'SCHEDULED' CHECK (status IN ('SCHEDULED','ACTIVE','SUPERSEDED')),
    set_by BIGINT NOT NULL,
    set_at TIMESTAMP NOT NULL,
    CHECK ((policy_type <> 'RETAIL_PRICE') OR (product_id IS NOT NULL AND category_id IS NULL AND unit_price IS NOT NULL)),
    CHECK ((policy_type <> 'MARKUP_RATE') OR ((num_nonnulls(product_id, category_id) = 1) AND markup_rate IS NOT NULL)),
    CHECK (policy_type NOT IN ('DISCOUNT_THRESHOLD','MINIMUM_MARGIN') OR rate IS NOT NULL),
    CHECK ((policy_type <> 'EXPIRY_WARNING') OR expiry_warning_days IS NOT NULL),
    CHECK (policy_type IN ('RETAIL_PRICE','MARKUP_RATE') OR (product_id IS NULL AND category_id IS NULL)),
    CONSTRAINT ux_pricing_policy_version UNIQUE NULLS NOT DISTINCT (policy_type, product_id, category_id, effective_from)
);

CREATE TABLE customer (
    id BIGSERIAL PRIMARY KEY,
    customer_name VARCHAR(150) NOT NULL,
    phone VARCHAR(20),
    address VARCHAR(255),
    tax_code VARCHAR(20),
    credit_status VARCHAR(20) NOT NULL DEFAULT 'NOT_GRANTED' CHECK (credit_status IN ('NOT_GRANTED','ELIGIBLE','REVOKED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
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

CREATE TABLE sales_order (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    customer_id BIGINT,
    customer_name_snapshot VARCHAR(150),
    customer_phone_snapshot VARCHAR(20),
    customer_address_snapshot VARCHAR(255),
    price_type VARCHAR(10) NOT NULL CHECK (price_type IN ('RETAIL','WHOLESALE')),
    discount_rate NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (discount_rate >= 0 AND discount_rate < 100),
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING_PICKING' CHECK (status IN ('AWAITING_PICKING','PICKED','CONFIRMED','COMPLETED','CANCELLED')),
    completion_type VARCHAR(10) CHECK (completion_type IN ('PAID','CREDIT')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    credit_decision VARCHAR(10) CHECK (credit_decision IN ('APPROVED','REJECTED')),
    approved_credit_amount BIGINT CHECK (approved_credit_amount > 0),
    credit_decided_by BIGINT,
    credit_decided_at TIMESTAMP,
    completed_by BIGINT,
    completed_at TIMESTAMP,
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CHECK ((status = 'COMPLETED') = (completion_type IS NOT NULL)),
    CHECK ((status <> 'CANCELLED') OR cancellation_reason IS NOT NULL),
    CHECK ((credit_decision = 'APPROVED') = (approved_credit_amount IS NOT NULL)),
    CHECK ((credit_decision IS NULL) = (credit_decided_by IS NULL))
);

CREATE TABLE sales_order_line (
    id BIGSERIAL PRIMARY KEY,
    sales_order_id BIGINT NOT NULL,
    line_no SMALLINT NOT NULL,
    product_id BIGINT NOT NULL,
    requested_qty INTEGER NOT NULL CHECK (requested_qty > 0),
    picked_qty INTEGER CHECK (picked_qty >= 0),
    delivered_qty INTEGER CHECK (delivered_qty >= 0),
    reference_price BIGINT NOT NULL CHECK (reference_price > 0),
    actual_unit_price BIGINT NOT NULL CHECK (actual_unit_price > 0),
    CHECK (delivered_qty IS NULL OR delivered_qty <= requested_qty),
    UNIQUE (sales_order_id, line_no),
    UNIQUE (sales_order_id, product_id)
);

CREATE TABLE receivable (
    id BIGSERIAL PRIMARY KEY,
    origin_type VARCHAR(20) NOT NULL CHECK (origin_type IN ('CREDIT_SALE','OPENING_BALANCE')),
    customer_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    sales_order_id BIGINT UNIQUE,
    original_amount BIGINT NOT NULL CHECK (original_amount > 0),
    due_date DATE,
    note VARCHAR(500),
    recognized_by BIGINT NOT NULL,
    recognized_at TIMESTAMP NOT NULL,
    settled_at TIMESTAMP,
    CHECK ((origin_type = 'CREDIT_SALE') = (sales_order_id IS NOT NULL))
);

CREATE TABLE payment (
    id BIGSERIAL PRIMARY KEY,
    payment_type VARCHAR(20) NOT NULL CHECK (payment_type IN ('ORDER_PAYMENT','REFUND','DEBT_PAYMENT')),
    sales_order_id BIGINT,
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
    CHECK (tendered_amount IS NULL OR tendered_amount >= amount),
    CHECK ((status <> 'REJECTED') OR rejection_reason IS NOT NULL)
);

CREATE TABLE payment_allocation (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    receivable_id BIGINT NOT NULL,
    allocated_amount BIGINT NOT NULL CHECK (allocated_amount > 0),
    UNIQUE (payment_id, receivable_id)
);

CREATE TABLE purchase_order (
    id BIGSERIAL PRIMARY KEY,
    supplier_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
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
    CHECK ((status <> 'CANCELLED') OR cancellation_reason IS NOT NULL)
);

CREATE TABLE purchase_order_line (
    id BIGSERIAL PRIMARY KEY,
    purchase_order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    ordered_qty INTEGER NOT NULL CHECK (ordered_qty > 0),
    purchase_price BIGINT NOT NULL CHECK (purchase_price > 0),
    UNIQUE (purchase_order_id, product_id)
);

CREATE TABLE goods_receipt_note (
    id BIGSERIAL PRIMARY KEY,
    receipt_type VARCHAR(20) NOT NULL CHECK (receipt_type IN ('SUPPLIER_DELIVERY','TRANSFER_IN','RETURN_TO_SOURCE')),
    purchase_order_id BIGINT,
    transfer_order_id BIGINT,
    delivery_document_no VARCHAR(50),
    delivery_document_date DATE,
    deliverer_name VARCHAR(100),
    vehicle_plate_no VARCHAR(20),
    arrived_at TIMESTAMP NOT NULL,
    status VARCHAR(25) NOT NULL DEFAULT 'AWAITING_INSPECTION' CHECK (status IN ('AWAITING_INSPECTION','AWAITING_CONFIRMATION','AWAITING_RECOUNT','CONFIRMED')),
    recorded_by BIGINT,
    submitted_at TIMESTAMP,
    recount_reason VARCHAR(255),
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    excess_decided_by BIGINT,
    excess_decided_at TIMESTAMP,
    CHECK ((receipt_type = 'SUPPLIER_DELIVERY') = (purchase_order_id IS NOT NULL)),
    CHECK ((receipt_type = 'SUPPLIER_DELIVERY') = (transfer_order_id IS NULL)),
    CHECK ((receipt_type <> 'SUPPLIER_DELIVERY') OR delivery_document_no IS NOT NULL),
    CHECK (confirmed_by IS NULL OR confirmed_by <> recorded_by),
    CHECK ((status = 'AWAITING_INSPECTION') OR (recorded_by IS NOT NULL AND submitted_at IS NOT NULL)),
    UNIQUE (purchase_order_id, delivery_document_no)
);

CREATE TABLE goods_receipt_note_line (
    id BIGSERIAL PRIMARY KEY,
    goods_receipt_note_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    document_qty INTEGER NOT NULL CHECK (document_qty >= 0),
    delivered_qty INTEGER NOT NULL CHECK (delivered_qty >= 0),
    passed_qty INTEGER NOT NULL CHECK (passed_qty >= 0),
    wrong_item_qty INTEGER NOT NULL DEFAULT 0 CHECK (wrong_item_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    excess_qty INTEGER NOT NULL DEFAULT 0 CHECK (excess_qty >= 0),
    excess_decision VARCHAR(20) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK (excess_decision IN ('NOT_APPLICABLE','AWAITING_OWNER','ACCEPTED','REJECTED')),
    unit_price BIGINT CHECK (unit_price > 0),
    manufacturer_lot_no VARCHAR(50),
    manufacture_date DATE,
    expiry_date DATE,
    CHECK (delivered_qty = ((passed_qty + wrong_item_qty) + damaged_qty)),
    CHECK (excess_qty <= passed_qty)
);

CREATE TABLE lot (
    id BIGSERIAL PRIMARY KEY,
    product_id BIGINT NOT NULL,
    source_type VARCHAR(15) NOT NULL CHECK (source_type IN ('RECEIPT','OPENING','COUNT_SURPLUS')),
    goods_receipt_note_line_id BIGINT UNIQUE,
    stock_count_id BIGINT,
    supplier_id BIGINT,
    store_id BIGINT NOT NULL,
    received_date DATE NOT NULL,
    purchase_unit_price BIGINT NOT NULL CHECK (purchase_unit_price >= 0),
    manufacturer_lot_no VARCHAR(50),
    manufacture_date DATE,
    expiry_date DATE,
    status VARCHAR(10) NOT NULL DEFAULT 'IN_STOCK' CHECK (status IN ('IN_STOCK','DEPLETED','EXPIRED')),
    CHECK ((source_type = 'RECEIPT') = (goods_receipt_note_line_id IS NOT NULL)),
    CHECK ((source_type = 'RECEIPT') = (stock_count_id IS NULL)),
    CHECK ((source_type = 'RECEIPT') OR supplier_id IS NULL)
);

CREATE TABLE lot_balance (
    id BIGSERIAL PRIMARY KEY,
    lot_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity INTEGER NOT NULL DEFAULT 0 CHECK (quantity >= 0),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (lot_id, store_id, stock_status)
);

CREATE TABLE replenishment_request (
    id BIGSERIAL PRIMARY KEY,
    requesting_store_id BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'AWAITING_COORDINATION' CHECK (status IN ('AWAITING_COORDINATION','IN_PROGRESS','CLOSED')),
    note VARCHAR(500),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    closed_at TIMESTAMP
);

CREATE TABLE replenishment_request_line (
    id BIGSERIAL PRIMARY KEY,
    replenishment_request_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    requested_qty INTEGER NOT NULL CHECK (requested_qty > 0),
    to_purchase_qty INTEGER NOT NULL DEFAULT 0 CHECK (to_purchase_qty >= 0),
    CHECK (to_purchase_qty <= requested_qty),
    UNIQUE (replenishment_request_id, product_id)
);

CREATE TABLE transfer_order (
    id BIGSERIAL PRIMARY KEY,
    replenishment_request_id BIGINT,
    source_store_id BIGINT NOT NULL,
    dest_store_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'AWAITING_DISPATCH' CHECK (status IN ('AWAITING_DISPATCH','IN_TRANSIT','RECONCILING','CLOSED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    dispatched_by BIGINT,
    dispatched_at TIMESTAMP,
    extra_dispatch_confirmed_by BIGINT,
    extra_dispatch_confirmed_at TIMESTAMP,
    closed_by BIGINT,
    closed_at TIMESTAMP,
    CHECK (source_store_id <> dest_store_id)
);

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
    source_type VARCHAR(10) NOT NULL CHECK (source_type IN ('SUPPLIER','TRANSFER')),
    goods_receipt_note_id BIGINT,
    transfer_order_id BIGINT,
    product_id BIGINT NOT NULL,
    short_qty INTEGER NOT NULL DEFAULT 0 CHECK (short_qty >= 0),
    wrong_item_qty INTEGER NOT NULL DEFAULT 0 CHECK (wrong_item_qty >= 0),
    damaged_qty INTEGER NOT NULL DEFAULT 0 CHECK (damaged_qty >= 0),
    rejected_excess_qty INTEGER NOT NULL DEFAULT 0 CHECK (rejected_excess_qty >= 0),
    return_required_qty INTEGER NOT NULL DEFAULT 0 CHECK (return_required_qty >= 0),
    replacement_required_qty INTEGER NOT NULL DEFAULT 0 CHECK (replacement_required_qty >= 0),
    status VARCHAR(15) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    opened_at TIMESTAMP NOT NULL,
    closure_type VARCHAR(25) CHECK (closure_type IN ('ALL_SETTLED','CLOSED_BY_AGREEMENT')),
    closure_reason VARCHAR(255),
    closed_by BIGINT,
    closed_at TIMESTAMP,
    CHECK ((source_type = 'SUPPLIER') = (goods_receipt_note_id IS NOT NULL)),
    CHECK ((source_type = 'TRANSFER') = (transfer_order_id IS NOT NULL)),
    CHECK (return_required_qty <= ((wrong_item_qty + damaged_qty) + rejected_excess_qty)),
    CHECK (replacement_required_qty <= (short_qty + return_required_qty)),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL)),
    CONSTRAINT discrepancy_case_goods_receipt_note_id_transfer_order_id_produc UNIQUE NULLS NOT DISTINCT (goods_receipt_note_id, transfer_order_id, product_id)
);

CREATE TABLE discrepancy_resolution_event (
    id BIGSERIAL PRIMARY KEY,
    discrepancy_case_id BIGINT NOT NULL,
    resolution_type VARCHAR(30) NOT NULL CHECK (resolution_type IN ('MOVED_TO_AWAITING_RETURN','RETURNED_TO_SUPPLIER','REPLACEMENT_RECEIVED','LATE_DELIVERY_RECEIVED','RECEIVED_BACK_AT_SOURCE','APPROVED_LOSS','CLOSED_BY_AGREEMENT')),
    quantity INTEGER NOT NULL CHECK (quantity >= 0),
    goods_receipt_note_id BIGINT,
    adjustment_request_id BIGINT,
    reason VARCHAR(255),
    recorded_by BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    CHECK (resolution_type NOT IN ('REPLACEMENT_RECEIVED','LATE_DELIVERY_RECEIVED','RECEIVED_BACK_AT_SOURCE') OR goods_receipt_note_id IS NOT NULL),
    CHECK ((resolution_type <> 'APPROVED_LOSS') OR adjustment_request_id IS NOT NULL)
);

CREATE TABLE stock_balance (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
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
    UNIQUE (store_id, product_id)
);

CREATE TABLE stock_movement (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_id BIGINT,
    inventory_period_id BIGINT NOT NULL,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity_change INTEGER NOT NULL CHECK (quantity_change <> 0),
    unit_cost NUMERIC(18,4) NOT NULL CHECK (unit_cost >= 0),
    value BIGINT NOT NULL,
    movement_type VARCHAR(30) NOT NULL CHECK (movement_type IN ('SALE_ISSUE','RECEIPT_CONFIRMED','EXCESS_ACCEPTED','EXCESS_REJECTED','RETURN_HOLD','SUPPLIER_RETURN','TRANSFER_DISPATCH','TRANSFER_EXTRA_DISPATCH','TRANSFER_RECEIPT','ADJUSTMENT','COUNT_ADJUSTMENT','OPENING_BALANCE')),
    sales_order_id BIGINT,
    goods_receipt_note_id BIGINT,
    transfer_order_id BIGINT,
    discrepancy_resolution_event_id BIGINT,
    adjustment_request_id BIGINT,
    stock_count_id BIGINT,
    reason VARCHAR(255),
    performed_by BIGINT NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    CHECK (num_nonnulls(sales_order_id, goods_receipt_note_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id, stock_count_id) = 1),
    CHECK ((movement_type IN ('COUNT_ADJUSTMENT','OPENING_BALANCE')) = (stock_count_id IS NOT NULL)),
    UNIQUE NULLS NOT DISTINCT (movement_type, sales_order_id, goods_receipt_note_id, transfer_order_id, discrepancy_resolution_event_id, adjustment_request_id, stock_count_id, store_id, product_id, lot_id, stock_status)
);

CREATE TABLE inventory_period (
    id BIGSERIAL PRIMARY KEY,
    period_month DATE NOT NULL UNIQUE CHECK (period_month = (date_trunc('month', period_month::timestamp with time zone))::date),
    from_date DATE NOT NULL,
    to_date DATE NOT NULL,
    previous_period_id BIGINT UNIQUE,
    status VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    closed_by BIGINT,
    closed_at TIMESTAMP,
    CHECK (from_date = period_month AND to_date >= from_date),
    CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);

CREATE TABLE stock_count_request (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('WHOLE','PRODUCTS','AREA')),
    scope_note VARCHAR(255),
    reason VARCHAR(255) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','DONE','CANCELLED')),
    requested_by BIGINT NOT NULL,
    requested_at TIMESTAMP NOT NULL DEFAULT NOW(),
    cancelled_at TIMESTAMP,
    CHECK ((status <> 'CANCELLED') OR cancelled_at IS NOT NULL)
);

CREATE TABLE stock_count_request_product (
    id BIGSERIAL PRIMARY KEY,
    stock_count_request_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    UNIQUE (stock_count_request_id, product_id)
);

CREATE TABLE stock_count (
    id BIGSERIAL PRIMARY KEY,
    store_id BIGINT NOT NULL,
    inventory_period_id BIGINT,
    count_type VARCHAR(10) NOT NULL CHECK (count_type IN ('OPENING','YEAR_END','CYCLE')),
    count_date DATE NOT NULL,
    stock_count_request_id BIGINT UNIQUE,
    scope_type VARCHAR(10) NOT NULL CHECK (scope_type IN ('WHOLE','PRODUCTS','AREA')),
    scope_note VARCHAR(255),
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','COUNTING','AWAITING_APPROVAL','COMPLETED','CANCELLED')),
    created_by BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    counting_started_at TIMESTAMP,
    confirmed_by BIGINT,
    confirmed_at TIMESTAMP,
    approved_by BIGINT,
    approved_at TIMESTAMP,
    decision_reason VARCHAR(255),
    cancelled_by BIGINT,
    cancelled_at TIMESTAMP,
    cancellation_reason VARCHAR(255),
    CHECK ((count_type = 'OPENING') = (inventory_period_id IS NULL)),
    CHECK ((status <> 'CANCELLED') OR cancellation_reason IS NOT NULL),
    CHECK (stock_count_request_id IS NULL OR (count_type = 'CYCLE'))
);

CREATE TABLE stock_count_line (
    id BIGSERIAL PRIMARY KEY,
    stock_count_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_id BIGINT,
    expiry_date DATE,
    book_qty INTEGER NOT NULL CHECK (book_qty >= 0),
    counted_qty INTEGER CHECK (counted_qty >= 0),
    counted_by BIGINT,
    counted_at TIMESTAMP,
    reason VARCHAR(255),
    recount_requested BOOLEAN NOT NULL DEFAULT FALSE,
    recount_reason VARCHAR(255),
    previous_book_qty INTEGER CHECK (previous_book_qty >= 0),
    previous_counted_qty INTEGER CHECK (previous_counted_qty >= 0),
    UNIQUE NULLS NOT DISTINCT (stock_count_id, product_id, lot_id),
    UNIQUE NULLS NOT DISTINCT (stock_count_id, product_id, lot_id, expiry_date)
);

CREATE TABLE period_stock_balance (
    id BIGSERIAL PRIMARY KEY,
    inventory_period_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
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
    CHECK (closing_qty = ((opening_qty + in_qty) - out_qty)),
    CHECK (closing_value = ((opening_value + in_value) - out_value)),
    UNIQUE (inventory_period_id, store_id, product_id)
);

CREATE TABLE adjustment_request (
    id BIGSERIAL PRIMARY KEY,
    adjustment_type VARCHAR(25) NOT NULL CHECK (adjustment_type IN ('STOCK_LOSS','DOCUMENT_CORRECTION')),
    store_id BIGINT NOT NULL,
    sales_order_id BIGINT,
    payment_id BIGINT,
    goods_receipt_note_id BIGINT,
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
    CHECK (num_nonnulls(sales_order_id, payment_id, goods_receipt_note_id, transfer_order_id, receivable_id) <= 1),
    CHECK ((adjustment_type <> 'DOCUMENT_CORRECTION') OR (num_nonnulls(sales_order_id, payment_id, goods_receipt_note_id, transfer_order_id, receivable_id) = 1))
);

CREATE TABLE adjustment_stock_line (
    id BIGSERIAL PRIMARY KEY,
    adjustment_request_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    lot_id BIGINT,
    stock_status VARCHAR(30) NOT NULL CHECK (stock_status IN ('AVAILABLE','AWAITING_INSPECTION','WRONG_ITEM','DAMAGED','AWAITING_SUPPLIER_RETURN','IN_TRANSIT')),
    quantity_change INTEGER NOT NULL CHECK (quantity_change <> 0),
    UNIQUE NULLS NOT DISTINCT (adjustment_request_id, store_id, product_id, lot_id, stock_status)
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
    task_type VARCHAR(25) NOT NULL CHECK (task_type IN ('PICKING','INSPECTION','DISPATCH_PREPARATION','COUNTING')),
    sales_order_id BIGINT,
    goods_receipt_note_id BIGINT,
    transfer_order_id BIGINT,
    stock_count_id BIGINT,
    assigned_to BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    assigned_at TIMESTAMP NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED' CHECK (status IN ('ASSIGNED','RESULT_SUBMITTED','DONE','CANCELLED')),
    result_submitted_at TIMESTAMP,
    CHECK (num_nonnulls(sales_order_id, goods_receipt_note_id, transfer_order_id, stock_count_id) = 1),
    CHECK ((task_type = 'PICKING') = (sales_order_id IS NOT NULL)),
    CHECK ((task_type = 'INSPECTION') = (goods_receipt_note_id IS NOT NULL)),
    CHECK ((task_type = 'DISPATCH_PREPARATION') = (transfer_order_id IS NOT NULL)),
    CHECK ((task_type = 'COUNTING') = (stock_count_id IS NOT NULL))
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

CREATE TABLE attachment (
    id BIGSERIAL PRIMARY KEY,
    owner_type VARCHAR(40) NOT NULL CHECK (owner_type IN ('GOODS_RECEIPT_NOTE','PAYMENT','RECEIVABLE','DISCREPANCY_CASE','DISCREPANCY_RESOLUTION_EVENT','ADJUSTMENT_REQUEST','TRANSFER_ORDER')),
    owner_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(100) NOT NULL,
    file_size BIGINT NOT NULL CHECK (file_size > 0),
    storage_key VARCHAR(500) NOT NULL UNIQUE,
    uploaded_by BIGINT NOT NULL,
    uploaded_at TIMESTAMP NOT NULL
);

CREATE TABLE audit_log (
    id BIGSERIAL PRIMARY KEY,
    entity_type VARCHAR(50) NOT NULL,
    entity_id BIGINT NOT NULL,
    action VARCHAR(50) NOT NULL,
    actor_id BIGINT,
    occurred_at TIMESTAMP NOT NULL,
    reason VARCHAR(500),
    old_data JSONB,
    new_data JSONB
);

-- Foreign keys
ALTER TABLE store ADD CONSTRAINT fk_store_copied_from_store_id FOREIGN KEY (copied_from_store_id) REFERENCES store(id);
ALTER TABLE store ADD CONSTRAINT fk_store_owner_manager_id FOREIGN KEY (owner_manager_id) REFERENCES user_account(id);
ALTER TABLE user_account ADD CONSTRAINT fk_user_account_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE user_account ADD CONSTRAINT fk_user_account_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE auth_session ADD CONSTRAINT fk_auth_session_user_id FOREIGN KEY (user_id) REFERENCES user_account(id);
ALTER TABLE part_category ADD CONSTRAINT fk_part_category_parent_id FOREIGN KEY (parent_id) REFERENCES part_category(id);
ALTER TABLE product ADD CONSTRAINT fk_product_category_id FOREIGN KEY (category_id) REFERENCES part_category(id);
ALTER TABLE product ADD CONSTRAINT fk_product_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE product_identifier ADD CONSTRAINT fk_product_identifier_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_image ADD CONSTRAINT fk_product_image_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_image ADD CONSTRAINT fk_product_image_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES user_account(id);
ALTER TABLE product_vehicle_model ADD CONSTRAINT fk_product_vehicle_model_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE product_vehicle_model ADD CONSTRAINT fk_product_vehicle_model_vehicle_model_id FOREIGN KEY (vehicle_model_id) REFERENCES vehicle_model(id);
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_category_id FOREIGN KEY (category_id) REFERENCES part_category(id);
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE pricing_policy ADD CONSTRAINT fk_pricing_policy_set_by FOREIGN KEY (set_by) REFERENCES user_account(id);
ALTER TABLE customer ADD CONSTRAINT fk_customer_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_completed_by FOREIGN KEY (completed_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_credit_decided_by FOREIGN KEY (credit_decided_by) REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE sales_order ADD CONSTRAINT fk_sales_order_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE sales_order_line ADD CONSTRAINT fk_sales_order_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE sales_order_line ADD CONSTRAINT fk_sales_order_line_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_customer_id FOREIGN KEY (customer_id) REFERENCES customer(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_recognized_by FOREIGN KEY (recognized_by) REFERENCES user_account(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE receivable ADD CONSTRAINT fk_receivable_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_verified_by FOREIGN KEY (verified_by) REFERENCES user_account(id);
ALTER TABLE payment_allocation ADD CONSTRAINT fk_payment_allocation_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE payment_allocation ADD CONSTRAINT fk_payment_allocation_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE purchase_order ADD CONSTRAINT fk_purchase_order_supplier_id FOREIGN KEY (supplier_id) REFERENCES supplier(id);
ALTER TABLE purchase_order_line ADD CONSTRAINT fk_purchase_order_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE purchase_order_line ADD CONSTRAINT fk_purchase_order_line_purchase_order_id FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id);
ALTER TABLE goods_receipt_note ADD CONSTRAINT fk_goods_receipt_note_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_note ADD CONSTRAINT fk_goods_receipt_note_excess_decided_by FOREIGN KEY (excess_decided_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_note ADD CONSTRAINT fk_goods_receipt_note_purchase_order_id FOREIGN KEY (purchase_order_id) REFERENCES purchase_order(id);
ALTER TABLE goods_receipt_note ADD CONSTRAINT fk_goods_receipt_note_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE goods_receipt_note ADD CONSTRAINT fk_goods_receipt_note_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE goods_receipt_note_line ADD CONSTRAINT fk_goods_receipt_note_line_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE goods_receipt_note_line ADD CONSTRAINT fk_goods_receipt_note_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_goods_receipt_note_line_id FOREIGN KEY (goods_receipt_note_line_id) REFERENCES goods_receipt_note_line(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE lot ADD CONSTRAINT fk_lot_supplier_id FOREIGN KEY (supplier_id) REFERENCES supplier(id);
ALTER TABLE lot_balance ADD CONSTRAINT fk_lot_balance_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE lot_balance ADD CONSTRAINT fk_lot_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE replenishment_request ADD CONSTRAINT fk_replenishment_request_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE replenishment_request ADD CONSTRAINT fk_replenishment_request_requesting_store_id FOREIGN KEY (requesting_store_id) REFERENCES store(id);
ALTER TABLE replenishment_request_line ADD CONSTRAINT fk_replenishment_request_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE replenishment_request_line ADD CONSTRAINT fk_replenishment_request_line_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_dest_store_id FOREIGN KEY (dest_store_id) REFERENCES store(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_dispatched_by FOREIGN KEY (dispatched_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_extra_dispatch_confirmed_by FOREIGN KEY (extra_dispatch_confirmed_by) REFERENCES user_account(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_replenishment_request_id FOREIGN KEY (replenishment_request_id) REFERENCES replenishment_request(id);
ALTER TABLE transfer_order ADD CONSTRAINT fk_transfer_order_source_store_id FOREIGN KEY (source_store_id) REFERENCES store(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_makeup_for_case_id FOREIGN KEY (makeup_for_case_id) REFERENCES discrepancy_case(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE transfer_order_line ADD CONSTRAINT fk_transfer_order_line_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE discrepancy_case ADD CONSTRAINT fk_discrepancy_case_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_discrepancy_case_id FOREIGN KEY (discrepancy_case_id) REFERENCES discrepancy_case(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE discrepancy_resolution_event ADD CONSTRAINT fk_discrepancy_resolution_event_recorded_by FOREIGN KEY (recorded_by) REFERENCES user_account(id);
ALTER TABLE stock_balance ADD CONSTRAINT fk_stock_balance_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_balance ADD CONSTRAINT fk_stock_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_discrepancy_resolution_event_id FOREIGN KEY (discrepancy_resolution_event_id) REFERENCES discrepancy_resolution_event(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_performed_by FOREIGN KEY (performed_by) REFERENCES user_account(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_movement ADD CONSTRAINT fk_stock_movement_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE inventory_period ADD CONSTRAINT fk_inventory_period_closed_by FOREIGN KEY (closed_by) REFERENCES user_account(id);
ALTER TABLE inventory_period ADD CONSTRAINT fk_inventory_period_previous_period_id FOREIGN KEY (previous_period_id) REFERENCES inventory_period(id);
ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_requested_by FOREIGN KEY (requested_by) REFERENCES user_account(id);
ALTER TABLE stock_count_request ADD CONSTRAINT fk_stock_count_request_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_count_request_product ADD CONSTRAINT fk_stock_count_request_product_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_count_request_product ADD CONSTRAINT fk_stock_count_request_product_stock_count_request_id FOREIGN KEY (stock_count_request_id) REFERENCES stock_count_request(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_approved_by FOREIGN KEY (approved_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_confirmed_by FOREIGN KEY (confirmed_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_created_by FOREIGN KEY (created_by) REFERENCES user_account(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_stock_count_request_id FOREIGN KEY (stock_count_request_id) REFERENCES stock_count_request(id);
ALTER TABLE stock_count ADD CONSTRAINT fk_stock_count_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE stock_count_line ADD CONSTRAINT fk_stock_count_line_counted_by FOREIGN KEY (counted_by) REFERENCES user_account(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_inventory_period_id FOREIGN KEY (inventory_period_id) REFERENCES inventory_period(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE period_stock_balance ADD CONSTRAINT fk_period_stock_balance_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_decided_by FOREIGN KEY (decided_by) REFERENCES user_account(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_requested_by FOREIGN KEY (requested_by) REFERENCES user_account(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE adjustment_request ADD CONSTRAINT fk_adjustment_request_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_lot_id FOREIGN KEY (lot_id) REFERENCES lot(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_product_id FOREIGN KEY (product_id) REFERENCES product(id);
ALTER TABLE adjustment_stock_line ADD CONSTRAINT fk_adjustment_stock_line_store_id FOREIGN KEY (store_id) REFERENCES store(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_adjustment_request_id FOREIGN KEY (adjustment_request_id) REFERENCES adjustment_request(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE adjustment_amount_line ADD CONSTRAINT fk_adjustment_amount_line_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_assigned_by FOREIGN KEY (assigned_by) REFERENCES user_account(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_assigned_to FOREIGN KEY (assigned_to) REFERENCES user_account(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_goods_receipt_note_id FOREIGN KEY (goods_receipt_note_id) REFERENCES goods_receipt_note(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_sales_order_id FOREIGN KEY (sales_order_id) REFERENCES sales_order(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_stock_count_id FOREIGN KEY (stock_count_id) REFERENCES stock_count(id);
ALTER TABLE warehouse_task ADD CONSTRAINT fk_warehouse_task_transfer_order_id FOREIGN KEY (transfer_order_id) REFERENCES transfer_order(id);
ALTER TABLE notification ADD CONSTRAINT fk_notification_recipient_id FOREIGN KEY (recipient_id) REFERENCES user_account(id);
ALTER TABLE attachment ADD CONSTRAINT fk_attachment_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES user_account(id);
ALTER TABLE audit_log ADD CONSTRAINT fk_audit_log_actor_id FOREIGN KEY (actor_id) REFERENCES user_account(id);

-- Indexes
CREATE INDEX ix_store_copied_from_store_id ON store (copied_from_store_id);
CREATE INDEX ix_store_owner_manager_id ON store (owner_manager_id);
CREATE UNIQUE INDEX ux_store_central ON store (is_central) WHERE is_central;
CREATE INDEX ix_user_account_store_id ON user_account (store_id);
CREATE INDEX ix_auth_session_previous_refresh_token_hash ON auth_session (previous_refresh_token_hash);
CREATE INDEX ix_auth_session_user_id ON auth_session (user_id);
CREATE INDEX ix_part_category_parent_id ON part_category (parent_id);
CREATE INDEX ix_product_category_id ON product (category_id);
CREATE UNIQUE INDEX ux_product_identifier_2 ON product_identifier (identifier_value) WHERE identifier_type = 'BARCODE';
CREATE INDEX ix_product_image_product_id ON product_image (product_id);
CREATE UNIQUE INDEX ux_product_image_1 ON product_image (product_id) WHERE is_primary = TRUE;
CREATE INDEX ix_product_vehicle_model_vehicle_model_id ON product_vehicle_model (vehicle_model_id);
CREATE INDEX ix_pricing_policy_1 ON pricing_policy (policy_type, status, effective_from);
CREATE INDEX ix_pricing_policy_category_id ON pricing_policy (category_id);
CREATE INDEX ix_pricing_policy_product_id ON pricing_policy (product_id);
CREATE INDEX ix_customer_1 ON customer (phone);
CREATE UNIQUE INDEX ux_supplier_name_ci ON supplier (lower(supplier_name));
CREATE INDEX ix_sales_order_4 ON sales_order (store_id, status);
CREATE INDEX ix_sales_order_confirmed_by ON sales_order (confirmed_by);
CREATE INDEX ix_sales_order_credit_decided_by ON sales_order (credit_decided_by);
CREATE INDEX ix_sales_order_customer_id ON sales_order (customer_id);
CREATE INDEX ix_sales_order_line_product_id ON sales_order_line (product_id);
CREATE INDEX ix_receivable_customer_id ON receivable (customer_id);
CREATE INDEX ix_receivable_store_id ON receivable (store_id);
CREATE INDEX ix_payment_sales_order_id ON payment (sales_order_id);
CREATE INDEX ix_purchase_order_replenishment_request_id ON purchase_order (replenishment_request_id);
CREATE INDEX ix_purchase_order_store_id ON purchase_order (store_id);
CREATE INDEX ix_purchase_order_supplier_id ON purchase_order (supplier_id);
CREATE INDEX ix_purchase_order_line_product_id ON purchase_order_line (product_id);
CREATE INDEX ix_goods_receipt_note_transfer_order_id ON goods_receipt_note (transfer_order_id);
CREATE INDEX ix_goods_receipt_note_line_product_id ON goods_receipt_note_line (product_id);
CREATE INDEX ix_lot_1 ON lot (product_id, expiry_date, received_date) WHERE status = 'IN_STOCK';
CREATE INDEX ix_lot_product_id ON lot (product_id);
CREATE INDEX ix_lot_stock_count_id ON lot (stock_count_id);
CREATE INDEX ix_lot_store_id ON lot (store_id);
CREATE INDEX ix_lot_supplier_id ON lot (supplier_id);
CREATE INDEX ix_lot_balance_store_id ON lot_balance (store_id);
CREATE INDEX ix_replenishment_request_requesting_store_id ON replenishment_request (requesting_store_id);
CREATE INDEX ix_replenishment_request_line_product_id ON replenishment_request_line (product_id);
CREATE INDEX ix_transfer_order_dest_store_id ON transfer_order (dest_store_id);
CREATE INDEX ix_transfer_order_replenishment_request_id ON transfer_order (replenishment_request_id);
CREATE INDEX ix_transfer_order_source_store_id ON transfer_order (source_store_id);
CREATE INDEX ix_transfer_order_line_makeup_for_case_id ON transfer_order_line (makeup_for_case_id);
CREATE INDEX ix_transfer_order_line_product_id ON transfer_order_line (product_id);
CREATE INDEX ix_discrepancy_case_product_id ON discrepancy_case (product_id);
CREATE INDEX ix_discrepancy_case_transfer_order_id ON discrepancy_case (transfer_order_id);
CREATE INDEX ix_discrepancy_resolution_event_adjustment_request_id ON discrepancy_resolution_event (adjustment_request_id);
CREATE INDEX ix_discrepancy_resolution_event_discrepancy_case_id ON discrepancy_resolution_event (discrepancy_case_id);
CREATE INDEX ix_discrepancy_resolution_event_goods_receipt_note_id ON discrepancy_resolution_event (goods_receipt_note_id);
CREATE INDEX ix_stock_balance_product_id ON stock_balance (product_id);
CREATE INDEX ix_stock_movement_3 ON stock_movement (store_id, product_id, occurred_at);
CREATE INDEX ix_stock_movement_adjustment_request_id ON stock_movement (adjustment_request_id);
CREATE INDEX ix_stock_movement_discrepancy_resolution_event_id ON stock_movement (discrepancy_resolution_event_id);
CREATE INDEX ix_stock_movement_goods_receipt_note_id ON stock_movement (goods_receipt_note_id);
CREATE INDEX ix_stock_movement_inventory_period_id ON stock_movement (inventory_period_id);
CREATE INDEX ix_stock_movement_lot_id ON stock_movement (lot_id);
CREATE INDEX ix_stock_movement_product_id ON stock_movement (product_id);
CREATE INDEX ix_stock_movement_sales_order_id ON stock_movement (sales_order_id);
CREATE INDEX ix_stock_movement_stock_count_id ON stock_movement (stock_count_id);
CREATE INDEX ix_stock_movement_transfer_order_id ON stock_movement (transfer_order_id);
CREATE INDEX ix_stock_count_request_1 ON stock_count_request (status, requested_at);
CREATE INDEX ix_stock_count_request_store_id ON stock_count_request (store_id);
CREATE INDEX ix_stock_count_request_product_product_id ON stock_count_request_product (product_id);
CREATE INDEX ix_stock_count_inventory_period_id ON stock_count (inventory_period_id);
CREATE INDEX ix_stock_count_store_id ON stock_count (store_id);
CREATE INDEX ix_stock_count_line_lot_id ON stock_count_line (lot_id);
CREATE INDEX ix_stock_count_line_product_id ON stock_count_line (product_id);
CREATE INDEX ix_period_stock_balance_product_id ON period_stock_balance (product_id);
CREATE INDEX ix_period_stock_balance_store_id ON period_stock_balance (store_id);
CREATE INDEX ix_adjustment_request_goods_receipt_note_id ON adjustment_request (goods_receipt_note_id);
CREATE INDEX ix_adjustment_request_payment_id ON adjustment_request (payment_id);
CREATE INDEX ix_adjustment_request_receivable_id ON adjustment_request (receivable_id);
CREATE INDEX ix_adjustment_request_sales_order_id ON adjustment_request (sales_order_id);
CREATE INDEX ix_adjustment_request_store_id ON adjustment_request (store_id);
CREATE INDEX ix_adjustment_request_transfer_order_id ON adjustment_request (transfer_order_id);
CREATE INDEX ix_adjustment_stock_line_lot_id ON adjustment_stock_line (lot_id);
CREATE INDEX ix_adjustment_stock_line_product_id ON adjustment_stock_line (product_id);
CREATE INDEX ix_adjustment_stock_line_store_id ON adjustment_stock_line (store_id);
CREATE INDEX ix_adjustment_amount_line_adjustment_request_id ON adjustment_amount_line (adjustment_request_id);
CREATE INDEX ix_adjustment_amount_line_payment_id ON adjustment_amount_line (payment_id);
CREATE INDEX ix_adjustment_amount_line_receivable_id ON adjustment_amount_line (receivable_id);
CREATE INDEX ix_warehouse_task_5 ON warehouse_task (assigned_to, status);
CREATE INDEX ix_warehouse_task_goods_receipt_note_id ON warehouse_task (goods_receipt_note_id);
CREATE INDEX ix_warehouse_task_sales_order_id ON warehouse_task (sales_order_id);
CREATE INDEX ix_warehouse_task_stock_count_id ON warehouse_task (stock_count_id);
CREATE INDEX ix_warehouse_task_transfer_order_id ON warehouse_task (transfer_order_id);
CREATE UNIQUE INDEX ux_warehouse_task_4 ON warehouse_task (task_type, sales_order_id, goods_receipt_note_id, transfer_order_id, stock_count_id) NULLS NOT DISTINCT WHERE status IN ('ASSIGNED','RESULT_SUBMITTED');
CREATE INDEX ix_notification_1 ON notification (recipient_id, is_read, created_at);
CREATE INDEX ix_attachment_1 ON attachment (owner_type, owner_id);
CREATE INDEX ix_audit_log_1 ON audit_log (entity_type, entity_id, occurred_at);
CREATE INDEX ix_audit_log_actor_id ON audit_log (actor_id);
