ALTER TABLE transfer_order
    ADD COLUMN discrepancy_acknowledged_by BIGINT REFERENCES user_account(id),
    ADD COLUMN discrepancy_acknowledged_at TIMESTAMP,
    ADD CONSTRAINT transfer_discrepancy_ack_pair CHECK (
        (discrepancy_acknowledged_by IS NULL) = (discrepancy_acknowledged_at IS NULL));
