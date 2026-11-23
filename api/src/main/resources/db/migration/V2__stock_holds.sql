-- Stock is reserved in single autocommit statements before the order transaction, so a popular
-- dish's row lock is held only inside one statement. Each reservation leaves a hold row; the order
-- transaction deletes its holds when the order commits. Holds without an order (the request failed
-- or the process died in between) are returned to stock by a sweeper.
create table stock_holds (
    id         bigint generated always as identity primary key,
    order_id   uuid        not null,
    dish_id    bigint      not null references dishes (id),
    quantity   integer     not null check (quantity > 0),
    created_at timestamptz not null default now()
);
create index stock_holds_order on stock_holds (order_id);
create index stock_holds_created on stock_holds (created_at);
