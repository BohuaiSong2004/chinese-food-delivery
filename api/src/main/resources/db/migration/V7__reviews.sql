-- One review per delivered order. The restaurant keeps a running sum and count (updated in the
-- same transaction as the review, and corrected when an admin hides one), so listing restaurants
-- never aggregates the reviews table.
create table reviews (
    order_id      uuid        primary key references orders (id),
    restaurant_id bigint      not null references restaurants (id),
    customer_id   bigint      not null references users (id),
    rating        smallint    not null check (rating between 1 and 5),
    comment       text        not null default '' check (length(comment) <= 1000),
    created_at    timestamptz not null default now(),
    reply         text                 check (length(reply) <= 1000),
    replied_at    timestamptz,
    hidden_at     timestamptz,
    hidden_reason text
);
create index reviews_restaurant on reviews (restaurant_id, created_at desc, order_id) where hidden_at is null;

alter table restaurants
    add column rating_sum   bigint  not null default 0 check (rating_sum >= 0),
    add column rating_count integer not null default 0 check (rating_count >= 0);
