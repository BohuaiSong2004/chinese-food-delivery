-- A hold also records which request made it. Two concurrent retries of the same request (same
-- customer and Idempotency-Key) then cannot both reserve stock: the second one's reservation
-- statement hits this unique index and is rolled back whole, decrement included.
alter table stock_holds add column customer_id bigint;
alter table stock_holds add column idempotency_key text;
create unique index stock_holds_request on stock_holds (customer_id, idempotency_key, dish_id);
