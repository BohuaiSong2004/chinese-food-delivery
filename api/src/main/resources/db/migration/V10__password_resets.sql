-- Password reset links. Only a SHA-256 of the token is stored (a database leak does not leak
-- usable links); tokens are single-use and short-lived.
create table password_resets (
    token_hash bytea       primary key,
    user_id    bigint      not null references users (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    used_at    timestamptz
);
create index password_resets_user on password_resets (user_id);
