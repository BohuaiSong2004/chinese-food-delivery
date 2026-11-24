-- Keyset pagination reads (owner, created_at, id) in descending order; these indexes serve each list
-- directly, so page 500 costs the same as page 1.
create index orders_customer_page on orders (customer_id, created_at desc, id desc);
create index orders_restaurant_page on orders (restaurant_id, created_at desc, id desc);
create index orders_rider_page on orders (rider_id, created_at desc, id desc) where rider_id is not null;
create index orders_page on orders (created_at desc, id desc);
drop index orders_customer;
