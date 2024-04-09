-- Where restaurants are and how far they deliver; where customers want food delivered.
alter table restaurants
    add column zip                text,
    add column lat                double precision check (lat between -90 and 90),
    add column lng                double precision check (lng between -180 and 180),
    add column delivery_radius_km numeric(4, 1) not null default 8 check (delivery_radius_km between 1 and 25);

-- A customer's saved addresses, geocoded when saved. Orders copy the text and coordinates, so
-- editing or deleting an address never changes an order that was already placed.
create table addresses (
    id          bigint generated always as identity primary key,
    user_id     bigint           not null references users (id) on delete cascade,
    label       text             not null,
    line1       text             not null,
    line2       text             not null default '',
    city        text             not null,
    zip         text             not null,
    lat         double precision not null check (lat between -90 and 90),
    lng         double precision not null check (lng between -180 and 180),
    notes       text             not null default '',
    created_at  timestamptz      not null default now(),
    archived_at timestamptz
);
create index addresses_user on addresses (user_id, created_at) where archived_at is null;

alter table orders
    add column delivery_lat double precision,
    add column delivery_lng double precision,
    add column distance_m   integer check (distance_m >= 0);
