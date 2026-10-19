-- Audit trail (NFR-S06). Copied from MotoFlow_Logical_3NF/schema_postgres.sql; keep both in sync.

-- rule: Rows are never updated or deleted
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

ALTER TABLE audit_log ADD CONSTRAINT fk_audit_log_actor_id FOREIGN KEY (actor_id) REFERENCES user_account(id);
CREATE INDEX ix_audit_log_1 ON audit_log (entity_type, entity_id, occurred_at);
