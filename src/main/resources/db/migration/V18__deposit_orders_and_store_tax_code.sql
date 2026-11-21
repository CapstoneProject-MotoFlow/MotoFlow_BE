-- PM 21/11 (task "Cọc bán buôn + 2 mẫu in"): a waiting order may carry a deposit, the seller's tax code is printed.

-- BE-2: the store's own tax code, printed as the seller on a sales slip (10 or 13 digits, optional).
ALTER TABLE store ADD COLUMN tax_code VARCHAR(20);

-- BE-3, BE-7: a deposit has an expiry date; when the customer loses it, who confirmed that and when.
ALTER TABLE sales_order ADD COLUMN deposit_expires_on DATE;
ALTER TABLE sales_order ADD COLUMN deposit_forfeited_at TIMESTAMP;
ALTER TABLE sales_order ADD COLUMN deposit_forfeited_by BIGINT REFERENCES user_account(id);
ALTER TABLE sales_order ADD CONSTRAINT ck_sales_order_deposit_forfeited
    CHECK ((deposit_forfeited_at IS NULL) = (deposit_forfeited_by IS NULL));

-- BE-3: DEPOSIT is money a customer leaves on a waiting order. The CHECK of V1 has the name PostgreSQL gave it.
ALTER TABLE payment DROP CONSTRAINT payment_payment_type_check;
ALTER TABLE payment ADD CONSTRAINT ck_payment_payment_type
    CHECK (payment_type IN ('ORDER_PAYMENT', 'REFUND', 'DEBT_PAYMENT', 'DEPOSIT'));
ALTER TABLE payment ADD CONSTRAINT ck_payment_deposit_order
    CHECK (payment_type <> 'DEPOSIT' OR sales_order_id IS NOT NULL);

-- BE-5: each release of a waiting order takes part of its deposit into the order it opens.
CREATE TABLE sales_deposit_application (
    id BIGSERIAL PRIMARY KEY,
    waiting_order_id BIGINT NOT NULL REFERENCES sales_order(id),
    sales_order_id BIGINT NOT NULL REFERENCES sales_order(id),
    amount BIGINT NOT NULL CHECK (amount > 0),
    applied_by BIGINT NOT NULL REFERENCES user_account(id),
    applied_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_sales_deposit_application_waiting ON sales_deposit_application (waiting_order_id);
CREATE INDEX idx_sales_deposit_application_order ON sales_deposit_application (sales_order_id);

-- BE-4: every change of a deposit's expiry date, oldest first.
CREATE TABLE sales_deposit_expiry_change (
    id BIGSERIAL PRIMARY KEY,
    sales_order_id BIGINT NOT NULL REFERENCES sales_order(id),
    old_date DATE NOT NULL,
    new_date DATE NOT NULL,
    changed_by BIGINT NOT NULL REFERENCES user_account(id),
    changed_at TIMESTAMP NOT NULL
);
CREATE INDEX idx_sales_deposit_expiry_change_order ON sales_deposit_expiry_change (sales_order_id);
