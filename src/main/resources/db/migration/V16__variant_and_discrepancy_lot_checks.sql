-- GVHD 20/11 PR 5 review: two invariants the application already keeps, now held by the schema.
-- A part is never the dented-goods variant of itself (a variant of a variant is refused by the service).
ALTER TABLE product ADD CONSTRAINT ck_product_variant_not_self
    CHECK (variant_of_product_id IS NULL OR variant_of_product_id <> id);

-- A lot made from a supplier discrepancy always has the supplier of the purchase order.
ALTER TABLE lot ADD CONSTRAINT ck_lot_discrepancy_supplier
    CHECK (source_type <> 'DISCREPANCY' OR supplier_id IS NOT NULL);
