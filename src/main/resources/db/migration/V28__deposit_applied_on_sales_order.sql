-- Plan 25/11: sales_deposit_application becomes columns of sales_order. Each order takes deposit at most once: an
-- order a partial release sold takes it when it is opened, a waiting order takes its own at its whole release. So the
-- one application row of an order fits on the order. The order a split order came from, kept so far only in
-- audit_log (version 1, split_from_order_id), becomes a column as well.
ALTER TABLE sales_order
    ADD COLUMN split_from_order_id BIGINT,
    ADD COLUMN deposit_applied_amount BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN deposit_applied_at TIMESTAMP,
    ADD CONSTRAINT fk_sales_order_split_from FOREIGN KEY (split_from_order_id) REFERENCES sales_order(id),
    ADD CONSTRAINT ck_sales_order_split_from CHECK (split_from_order_id IS NULL OR split_from_order_id <> id),
    ADD CONSTRAINT ck_sales_order_deposit_applied CHECK (deposit_applied_amount >= 0
        AND (deposit_applied_amount = 0) = (deposit_applied_at IS NULL));

CREATE INDEX idx_sales_order_split_from ON sales_order (split_from_order_id) WHERE split_from_order_id IS NOT NULL;

-- Where each split order came from, as its version 1 recorded it.
UPDATE sales_order o SET split_from_order_id = s.parent
FROM (SELECT a.entity_id, max((a.new_data ->> 'split_from_order_id')::BIGINT) AS parent
      FROM audit_log a
      WHERE a.entity_type = 'SALES_ORDER' AND a.action = 'SALES_ORDER_CREATED'
        AND a.new_data ->> 'split_from_order_id' IS NOT NULL
      GROUP BY a.entity_id) s
WHERE s.entity_id = o.id;

-- The rows must fit what the columns can hold: one application per order, and an order taking deposit from another
-- waiting order must be split from it.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM sales_deposit_application GROUP BY sales_order_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V28: an order took deposit more than once';
    END IF;
    IF EXISTS (SELECT 1 FROM sales_deposit_application a JOIN sales_order o ON o.id = a.sales_order_id
               WHERE a.waiting_order_id <> a.sales_order_id
                 AND o.split_from_order_id IS DISTINCT FROM a.waiting_order_id) THEN
        RAISE EXCEPTION 'V28: an order took deposit from a waiting order it was not split from';
    END IF;
END $$;

UPDATE sales_order o SET deposit_applied_amount = a.amount, deposit_applied_at = a.applied_at
FROM sales_deposit_application a
WHERE a.sales_order_id = o.id;

-- What a waiting order gave of its deposit is read back as its own share (whole release) plus what the orders split
-- from it took, leaving out split orders that had a deposit of their own (a waiting order made from a short picking).
-- It must give what the old rows held, order by order and in total.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM sales_order o
        WHERE (o.deposit_expires_on IS NOT NULL
               OR EXISTS (SELECT 1 FROM sales_deposit_application a WHERE a.waiting_order_id = o.id))
          AND o.deposit_applied_amount
              + (SELECT coalesce(sum(c.deposit_applied_amount), 0) FROM sales_order c
                 WHERE c.split_from_order_id = o.id AND c.deposit_expires_on IS NULL)
              <> (SELECT coalesce(sum(a.amount), 0) FROM sales_deposit_application a
                  WHERE a.waiting_order_id = o.id)
    ) OR (SELECT coalesce(sum(deposit_applied_amount), 0) FROM sales_order)
         <> (SELECT coalesce(sum(amount), 0) FROM sales_deposit_application) THEN
        RAISE EXCEPTION 'V28: deposit applied on sales_order differs from sales_deposit_application';
    END IF;
END $$;

DROP TABLE sales_deposit_application;
