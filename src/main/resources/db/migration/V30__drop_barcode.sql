-- 26/11: barcodes are removed; the old barcodes are not kept. The table only holds OEM codes now, so it is renamed
-- to product_oem_code and the identifier_type column is dropped.
DELETE FROM product_identifier WHERE identifier_type = 'BARCODE';
DROP INDEX ux_product_identifier_2;
-- Dropping the column also drops its CHECK and UNIQUE (product_id, identifier_type, identifier_value).
ALTER TABLE product_identifier DROP COLUMN identifier_type;
ALTER TABLE product_identifier RENAME TO product_oem_code;
ALTER TABLE product_oem_code RENAME COLUMN identifier_value TO oem_code;
ALTER TABLE product_oem_code ADD CONSTRAINT ux_product_oem_code UNIQUE (product_id, oem_code);
ALTER TABLE product_oem_code RENAME CONSTRAINT fk_product_identifier_product_id TO fk_product_oem_code_product_id;
ALTER INDEX product_identifier_pkey RENAME TO product_oem_code_pkey;
ALTER SEQUENCE product_identifier_id_seq RENAME TO product_oem_code_id_seq;
