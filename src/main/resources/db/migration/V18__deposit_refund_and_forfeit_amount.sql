-- PM 21/11 (task "Cọc bán buôn + 2 mẫu in", BE-5..8): deposit given back and deposit kept.

-- BE-5: what the last release of a waiting order gives back of a deposit larger than the order is a DEPOSIT_REFUND,
-- kept apart from the refunds of what an order was paid.
ALTER TABLE payment DROP CONSTRAINT ck_payment_payment_type;
ALTER TABLE payment ADD CONSTRAINT ck_payment_payment_type
    CHECK (payment_type IN ('ORDER_PAYMENT', 'REFUND', 'DEBT_PAYMENT', 'DEPOSIT', 'DEPOSIT_REFUND'));
ALTER TABLE payment DROP CONSTRAINT ck_payment_deposit_order;
ALTER TABLE payment ADD CONSTRAINT ck_payment_deposit_order
    CHECK (payment_type NOT IN ('DEPOSIT', 'DEPOSIT_REFUND') OR sales_order_id IS NOT NULL);

-- BE-7: how much of the deposit the store kept when the customer lost it (sales report, BE-6).
ALTER TABLE sales_order ADD COLUMN deposit_forfeited_amount BIGINT;
ALTER TABLE sales_order DROP CONSTRAINT ck_sales_order_deposit_forfeited;
ALTER TABLE sales_order ADD CONSTRAINT ck_sales_order_deposit_forfeited
    CHECK ((deposit_forfeited_at IS NULL) = (deposit_forfeited_by IS NULL)
        AND (deposit_forfeited_at IS NULL) = (deposit_forfeited_amount IS NULL)
        AND (deposit_forfeited_amount IS NULL OR deposit_forfeited_amount > 0));
