-- Góp ý UI 21/11 (item 12): damaged goods are called "hàng lỗi", not "hàng móp". The "-M" SKUs stay as they are.
UPDATE product
   SET part_name = replace(part_name, ' (hàng móp)', ' (hàng lỗi)')
 WHERE variant_of_product_id IS NOT NULL
   AND part_name LIKE '% (hàng móp)';
