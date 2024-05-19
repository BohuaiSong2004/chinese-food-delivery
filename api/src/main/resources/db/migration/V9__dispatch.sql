-- Offers of a delivery to one rider at a time. The partial unique indexes are the concurrency
-- control: two replicas dispatching the same order (or offering two orders to one rider) cannot
-- both succeed; the loser's insert fails and it moves on.
create table dispatch_offers (
    id           bigint generated always as identity primary key,
    order_id     uuid        not null references orders (id),
    rider_id     bigint      not null references users (id),
    status       text        not null check (status in ('OFFERED', 'ACCEPTED', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    distance_m   integer     not null check (distance_m >= 0),
    offered_at   timestamptz not null default now(),
    expires_at   timestamptz not null,
    responded_at timestamptz
);
create unique index dispatch_open_per_order on dispatch_offers (order_id) where status = 'OFFERED';
create unique index dispatch_open_per_rider on dispatch_offers (rider_id) where status = 'OFFERED';
create index dispatch_expiry on dispatch_offers (expires_at) where status = 'OFFERED';
create index dispatch_order on dispatch_offers (order_id);
