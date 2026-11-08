-- BR-45 (review of PR #29 and #31, R-06). A transaction reference is recorded once among payments and refunds that
-- were not rejected, whatever the case of its letters. The services check this before saving; the index stops two
-- requests sent at the same moment from both passing that check. Same condition as
-- PaymentRepository.findByTransactionReference. A rejected row leaves the index, so its reference can be used again.
CREATE UNIQUE INDEX ux_payment_reference ON payment (lower(transaction_reference))
    WHERE transaction_reference IS NOT NULL AND status <> 'REJECTED';

-- BR-53 (review of PR #29, R-07). What is still owed on a receivable sums its allocations; V1 indexes them only by
-- payment, through UNIQUE (payment_id, receivable_id).
CREATE INDEX ix_payment_allocation_receivable ON payment_allocation (receivable_id);
