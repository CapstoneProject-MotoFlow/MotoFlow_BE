-- Review 31/10.
-- 1. The Owner may also manage a store (stores need a manager before they open, UC-06).
ALTER TABLE store ADD COLUMN owner_manager_id BIGINT;
ALTER TABLE store ADD CONSTRAINT fk_store_owner_manager_id FOREIGN KEY (owner_manager_id) REFERENCES user_account(id);
CREATE INDEX ix_store_owner_manager_id ON store (owner_manager_id);

-- 2. Supplier codes are generated as NCC001, NCC002, ... (UC-31); continue after the codes already in use.
CREATE SEQUENCE supplier_code_seq;
SELECT setval('supplier_code_seq',
              COALESCE((SELECT max(substring(supplier_code FROM 4)::BIGINT) FROM supplier
                        WHERE supplier_code ~ '^NCC[0-9]{1,15}$'), 0) + 1, false);
