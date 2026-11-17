-- FE UI fixes 12/11 (UC-53, UC-55): a free-text note the seller writes on the order, for example what the customer
-- asked for. It is a memo only: changing it alone saves no new version and keeps the confirmation (BR-36).
ALTER TABLE sales_order ADD COLUMN note VARCHAR(500);
