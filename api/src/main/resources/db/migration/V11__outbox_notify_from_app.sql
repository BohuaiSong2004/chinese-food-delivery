-- The insert trigger ran pg_notify inside every business transaction. A transaction that has issued
-- NOTIFY takes a cluster-wide lock at commit and keeps it through its WAL flush, so every order,
-- payment and status change queued behind every other one (pg_stat_activity: Lock/object on COMMIT).
-- The API now wakes its own publisher after commit and sends a coalesced NOTIFY for other replicas
-- from outside the business transactions (OutboxWakeup).
drop trigger outbox_notify on outbox;
drop function outbox_notify();
