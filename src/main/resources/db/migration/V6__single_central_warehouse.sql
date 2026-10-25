-- BR-04: the chain has at most one central warehouse (ux_warehouse_1 in MotoFlow_Logical_3NF/schema_postgres.sql).
-- StoreService checks this first for a readable message; the index stops parallel requests that both pass that check.
CREATE UNIQUE INDEX ux_warehouse_1 ON warehouse (is_central) WHERE is_central = TRUE;
