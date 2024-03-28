-- Publishers claim an event only when no earlier event of the same aggregate is still unpublished
-- (OutboxPublisher.drainOnce); this index answers that check.
create index outbox_pending_aggregate on outbox (topic, aggregate_id, id) where published_at is null;
