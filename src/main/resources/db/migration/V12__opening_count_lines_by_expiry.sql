-- UC-96, BR-86: an opening count has one line per part and expiry date, none of them on a lot yet.
-- The (stock_count_id, product_id, lot_id) key allowed only one line without a lot per part, so it goes;
-- (stock_count_id, product_id, lot_id, expiry_date) stays and still keeps every other count's lines unique.
ALTER TABLE stock_count_line DROP CONSTRAINT stock_count_line_stock_count_id_product_id_lot_id_key;
