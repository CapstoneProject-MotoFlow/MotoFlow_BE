-- Logical design 15/10: updated_at defaults to NOW() and foreign keys get an index
-- (PostgreSQL does not create one), except actor columns (*_by).
ALTER TABLE store ALTER COLUMN updated_at SET DEFAULT NOW();
ALTER TABLE warehouse ALTER COLUMN updated_at SET DEFAULT NOW();
ALTER TABLE user_account ALTER COLUMN updated_at SET DEFAULT NOW();

CREATE INDEX ix_store_copied_from_store_id ON store (copied_from_store_id);
CREATE INDEX ix_user_account_store_id ON user_account (store_id);
CREATE INDEX ix_permission_history_user_id ON permission_history (user_id);
CREATE INDEX ix_audit_log_actor_id ON audit_log (actor_id);
