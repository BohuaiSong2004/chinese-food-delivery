create table intents (
    id              text primary key,
    idempotency_key text        not null unique,
    amount          integer     not null check (amount > 0),
    currency        text        not null,
    description     text        not null,
    reference       text        not null,
    return_url      text        not null,
    status          text        not null check (status in ('requires_action', 'succeeded', 'failed', 'canceled')),
    refunded        integer     not null default 0,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now()
);

create table refunds (
    id              text primary key,
    idempotency_key text        not null unique,
    intent_id       text        not null references intents (id),
    amount          integer     not null check (amount > 0),
    status          text        not null,
    created_at      timestamptz not null default now()
);

create table webhook_deliveries (
    id              bigint generated always as identity primary key,
    event_id        text        not null,
    type            text        not null,
    payload         text        not null,
    attempts        integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    delivered_at    timestamptz,
    last_error      text,
    created_at      timestamptz not null default now()
);
create index webhook_deliveries_due on webhook_deliveries (next_attempt_at) where delivered_at is null;
