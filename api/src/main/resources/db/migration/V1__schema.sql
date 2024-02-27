-- Wokflow core schema. All timestamps are timestamptz (UTC on the wire).

create extension if not exists pg_trgm;

-- ---------------------------------------------------------------- accounts
create table users (
    id            bigint generated always as identity primary key,
    email         text        not null,
    password_hash text        not null,
    display_name  text        not null,
    role          text        not null check (role in ('CUSTOMER', 'MERCHANT', 'RIDER', 'ADMIN')),
    created_at    timestamptz not null default now(),
    constraint users_email_lower check (email = lower(email))
);
create unique index users_email_key on users (email);

-- Refresh tokens are the only server-side auth state. Access tokens are
-- stateless ES256 JWTs; a refresh token is stored as a SHA-256 hash and
-- rotated on every use. Re-using a rotated token revokes its whole family.
create table refresh_tokens (
    id          uuid primary key,
    user_id     bigint      not null references users (id) on delete cascade,
    family_id   uuid        not null,
    token_hash  bytea       not null unique,
    expires_at  timestamptz not null,
    created_at  timestamptz not null default now(),
    rotated_at  timestamptz,
    revoked_at  timestamptz
);
create index refresh_tokens_family on refresh_tokens (family_id);
create index refresh_tokens_user on refresh_tokens (user_id);

-- ------------------------------------------------------------- restaurants
create table restaurants (
    id                bigint generated always as identity primary key,
    slug              text        not null unique,
    name              text        not null,
    name_zh           text        not null,
    description       text        not null default '',
    cuisine           text        not null,
    tags              text[]      not null default '{}',
    neighborhood      text        not null,
    address           text        not null,
    delivery_fee_cents integer    not null check (delivery_fee_cents >= 0),
    min_order_cents   integer     not null default 0 check (min_order_cents >= 0),
    prep_minutes      integer     not null default 20 check (prep_minutes > 0),
    commission_bps    integer     not null default 1500 check (commission_bps between 0 and 5000),
    opens_at          time        not null,
    closes_at         time        not null,   -- may be earlier than opens_at (open past midnight)
    open_24h          boolean     not null default false,
    time_zone         text        not null default 'America/Los_Angeles',
    accepting_orders  boolean     not null default true,
    version           bigint      not null default 1,
    menu_version      bigint      not null default 1,  -- bumped by any menu change; part of the cache key
    updated_at        timestamptz not null default now()
);

create table restaurant_staff (
    restaurant_id bigint not null references restaurants (id) on delete cascade,
    user_id       bigint not null references users (id) on delete cascade,
    primary key (restaurant_id, user_id)
);
create index restaurant_staff_user on restaurant_staff (user_id);

create table menu_categories (
    id            bigint generated always as identity primary key,
    restaurant_id bigint  not null references restaurants (id) on delete cascade,
    name          text    not null,
    name_zh       text    not null,
    position      integer not null,
    unique (restaurant_id, name)
);

create table dishes (
    id               bigint generated always as identity primary key,
    restaurant_id    bigint      not null references restaurants (id) on delete cascade,
    category_id      bigint      not null references menu_categories (id),
    name             text        not null,
    name_zh          text        not null,
    description      text        not null default '',
    price_cents      integer     not null check (price_cents > 0),
    tags             text[]      not null default '{}',
    spice_level      smallint    not null default 0 check (spice_level between 0 and 3),
    available        boolean     not null default true,
    -- Limited dishes ("only 40 soup dumpling baskets a day"): daily_stock is
    -- the target, stock_remaining is decremented atomically by orders and
    -- reset once per local day (stock_day).
    daily_stock      integer              check (daily_stock is null or daily_stock > 0),
    stock_remaining  integer              check (stock_remaining is null or stock_remaining >= 0),
    stock_day        date,
    version          bigint      not null default 1,
    updated_at       timestamptz not null default now(),
    unique (restaurant_id, name),
    constraint dishes_stock_pair check ((daily_stock is null) = (stock_remaining is null))
);
create index dishes_restaurant on dishes (restaurant_id);
create index dishes_name_trgm on dishes using gin (name gin_trgm_ops);
create index dishes_name_zh_trgm on dishes using gin (name_zh gin_trgm_ops);

-- ------------------------------------------------------------------ orders
create table orders (
    id                 uuid primary key,
    customer_id        bigint      not null references users (id),
    restaurant_id      bigint      not null references restaurants (id),
    status             text        not null check (status in (
        'PENDING_PAYMENT', 'PAID', 'ACCEPTED', 'READY', 'PICKED_UP', 'DELIVERED', 'CANCELLED', 'REJECTED')),
    subtotal_cents     integer     not null check (subtotal_cents > 0),
    delivery_fee_cents integer     not null check (delivery_fee_cents >= 0),
    commission_cents   integer     not null check (commission_cents >= 0),
    total_cents        integer     not null check (total_cents = subtotal_cents + delivery_fee_cents),
    delivery_address   text        not null,
    delivery_notes     text        not null default '',
    rider_id           bigint               references users (id),
    idempotency_key    text        not null,
    request_hash       text        not null,  -- the same key with a different body is refused
    cancel_reason      text,
    payment_deadline   timestamptz not null,
    version            integer     not null default 1,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    unique (customer_id, idempotency_key)
);
create index orders_customer on orders (customer_id, created_at desc);
create index orders_restaurant_active on orders (restaurant_id, created_at desc);
create index orders_pending_deadline on orders (payment_deadline) where status = 'PENDING_PAYMENT';
create index orders_rider_jobs on orders (updated_at) where rider_id is null and status in ('ACCEPTED', 'READY');
create index orders_rider on orders (rider_id, updated_at desc) where rider_id is not null;

create table order_items (
    order_id         uuid    not null references orders (id) on delete cascade,
    line_no          integer not null,
    dish_id          bigint  not null references dishes (id),
    name             text    not null,
    name_zh          text    not null,
    unit_price_cents integer not null check (unit_price_cents > 0),
    quantity         integer not null check (quantity between 1 and 50),
    primary key (order_id, line_no)
);
create index order_items_dish on order_items (dish_id);

create table order_status_history (
    order_id   uuid        not null references orders (id) on delete cascade,
    version    integer     not null,
    status     text        not null,
    actor_id   bigint,
    note       text,
    at         timestamptz not null default now(),
    primary key (order_id, version)
);

-- ---------------------------------------------------------------- payments
create table payments (
    id                 uuid primary key,
    order_id           uuid        not null unique references orders (id),
    provider_intent_id text unique,
    checkout_url       text,
    status             text        not null check (status in (
        'CREATING', 'REQUIRES_ACTION', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    amount_cents       integer     not null,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

-- Webhook de-duplication: providers deliver at least once.
create table provider_events (
    event_id    text primary key,
    type        text        not null,
    received_at timestamptz not null default now()
);

create table refunds (
    id                 uuid primary key,
    order_id           uuid        not null unique references orders (id),
    payment_id         uuid        not null references payments (id),
    amount_cents       integer     not null check (amount_cents > 0),
    reason             text        not null,
    status             text        not null check (status in ('REQUESTED', 'SUCCEEDED', 'FAILED')),
    provider_refund_id text,
    attempts           integer     not null default 0,
    next_attempt_at    timestamptz not null default now(),
    last_error         text,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);
create index refunds_due on refunds (next_attempt_at) where status = 'REQUESTED';

-- ------------------------------------------------------------------ ledger
-- Double-entry, append-only. A line amount is positive for a debit and
-- negative for a credit; every entry must sum to zero (checked at commit by
-- a deferred constraint trigger). Balances are derived from lines, so there
-- is no hot "balance" row that every order would have to lock.
create table ledger_accounts (
    id            bigint generated always as identity primary key,
    code          text   not null unique,
    kind          text   not null check (kind in ('ASSET', 'LIABILITY', 'REVENUE')),
    name          text   not null,
    restaurant_id bigint unique references restaurants (id)
);

create table ledger_entries (
    id         bigint generated always as identity primary key,
    kind       text        not null check (kind in ('CAPTURE', 'REFUND', 'PAYOUT')),
    ref        text        not null,
    order_id   uuid                 references orders (id),
    memo       text        not null default '',
    created_at timestamptz not null default now(),
    unique (kind, ref)  -- exactly-once posting per business event
);
create index ledger_entries_order on ledger_entries (order_id);

create table ledger_lines (
    entry_id     bigint not null references ledger_entries (id),
    line_no      smallint not null,
    account_id   bigint not null references ledger_accounts (id),
    amount_cents bigint not null check (amount_cents <> 0),
    primary key (entry_id, line_no)
);
create index ledger_lines_account on ledger_lines (account_id, entry_id);

create function ledger_assert_balanced() returns trigger
    language plpgsql as
$$
declare
    total bigint;
begin
    select coalesce(sum(amount_cents), 0) into total from ledger_lines where entry_id = new.entry_id;
    if total <> 0 then
        raise exception 'ledger entry % is unbalanced by % cents', new.entry_id, total
            using errcode = 'check_violation';
    end if;
    return null;
end;
$$;

create constraint trigger ledger_lines_balanced
    after insert on ledger_lines
    deferrable initially deferred
    for each row execute function ledger_assert_balanced();

create function ledger_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'ledger tables are append-only (% on %)', tg_op, tg_table_name
        using errcode = 'insufficient_privilege';
end;
$$;

create trigger ledger_entries_append_only before update or delete on ledger_entries
    for each row execute function ledger_append_only();
create trigger ledger_lines_append_only before update or delete on ledger_lines
    for each row execute function ledger_append_only();

create table payouts (
    id            uuid primary key,
    restaurant_id bigint      not null references restaurants (id),
    amount_cents  bigint      not null check (amount_cents > 0),
    requested_by  bigint      not null references users (id),
    created_at    timestamptz not null default now()
);
create index payouts_restaurant on payouts (restaurant_id, created_at desc);

-- ------------------------------------------------------------------ outbox
-- Events are written in the same transaction as the state change and
-- published after commit (Redis pub/sub for live status, Elasticsearch
-- indexing, hot-menu counters). Handlers are idempotent.
create table outbox (
    id              bigint generated always as identity primary key,
    topic           text        not null,
    aggregate_id    text        not null,
    payload         jsonb       not null,
    created_at      timestamptz not null default now(),
    published_at    timestamptz,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text
);
create index outbox_pending on outbox (id) where published_at is null;
create index outbox_published on outbox (published_at) where published_at is not null;

create function outbox_notify() returns trigger
    language plpgsql as
$$
begin
    perform pg_notify('outbox', '');
    return null;
end;
$$;

create trigger outbox_notify after insert on outbox
    for each statement execute function outbox_notify();

insert into ledger_accounts (code, kind, name) values
    ('provider_clearing', 'ASSET', 'Funds held at the payment provider'),
    ('platform_commission', 'REVENUE', 'Platform commission'),
    ('delivery_fees', 'REVENUE', 'Delivery fees');
