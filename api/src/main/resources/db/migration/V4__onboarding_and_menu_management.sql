-- Restaurants join the platform through an application that an admin reviews. Existing (seeded)
-- restaurants are LIVE. Only LIVE restaurants are listed, searchable and can take orders; a
-- SUSPENDED one keeps its menu, ledger and order history but disappears from the storefront.
alter table restaurants
    add column status       text        not null default 'LIVE'
        check (status in ('PENDING', 'LIVE', 'REJECTED', 'SUSPENDED')),
    add column status_note  text,
    add column submitted_at timestamptz,
    add column reviewed_by  bigint      references users (id),
    add column reviewed_at  timestamptz;
create index restaurants_pending on restaurants (submitted_at) where status = 'PENDING';

-- Riders sign up themselves but cannot take deliveries until an admin approves them. A rider who
-- was turned down keeps approved = false with reviewed_at set (pending = never reviewed).
alter table users
    add column approved    boolean     not null default true,
    add column reviewed_at timestamptz,
    add column review_note text;
create index users_pending_riders on users (created_at) where role = 'RIDER' and not approved and reviewed_at is null;

-- Who did what in the admin console (approvals, suspensions, payouts on behalf of a merchant...).
create table audit_log (
    id       bigint generated always as identity primary key,
    actor_id bigint      not null references users (id),
    action   text        not null,
    subject  text        not null,
    note     text,
    at       timestamptz not null default now()
);
create index audit_log_at on audit_log (at desc);

-- Menu items are archived, never deleted: past orders keep pointing at them. A name only has to be
-- unique among the items that are still on the menu, so an archived dish can be re-created.
alter table dishes add column archived_at timestamptz;
alter table dishes drop constraint dishes_restaurant_id_name_key;
create unique index dishes_active_name on dishes (restaurant_id, name) where archived_at is null;

alter table menu_categories add column archived_at timestamptz;
alter table menu_categories add column version bigint not null default 1;
alter table menu_categories drop constraint menu_categories_restaurant_id_name_key;
create unique index menu_categories_active_name on menu_categories (restaurant_id, name) where archived_at is null;
