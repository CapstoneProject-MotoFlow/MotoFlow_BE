-- Receivable keeps a due date; a debt payment can settle several receivables (payment_allocation).
ALTER TABLE receivable ADD COLUMN due_date DATE;

CREATE TABLE payment_allocation (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT NOT NULL,
    receivable_id BIGINT NOT NULL,
    allocated_amount BIGINT NOT NULL CHECK (allocated_amount > 0),
    UNIQUE (payment_id, receivable_id)
);
ALTER TABLE payment_allocation ADD CONSTRAINT fk_payment_allocation_payment_id FOREIGN KEY (payment_id) REFERENCES payment(id);
ALTER TABLE payment_allocation ADD CONSTRAINT fk_payment_allocation_receivable_id FOREIGN KEY (receivable_id) REFERENCES receivable(id);

-- Existing debt payments move to one allocation each
INSERT INTO payment_allocation (payment_id, receivable_id, allocated_amount)
SELECT id, receivable_id, amount FROM payment WHERE receivable_id IS NOT NULL;

ALTER TABLE payment DROP COLUMN receivable_id;
