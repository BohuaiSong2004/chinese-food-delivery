#!/usr/bin/env bash
# Database-level checks after peak.js (run from the repository root).
set -euo pipefail
LIMITED_STOCK=${LIMITED_STOCK:-100}
START=$(cat loadtest/.run-start)
q() { docker compose exec -T postgres psql -U wokflow -tAc "$1"; }
echo "deadlocks (cumulative since the database started): $(q "select deadlocks from pg_stat_database where datname='wokflow'")"
echo "outbox not yet delivered: $(q "select count(*) from outbox where published_at is null")"
echo "orders this run by status: $(q "select string_agg(status || '=' || n, ' ') from (select status, count(*) n from orders where created_at >= '$START' group by status order by status) s")"
SOLD=$(q "select coalesce(sum(i.quantity),0) from order_items i join orders o on o.id = i.order_id join dishes d on d.id = i.dish_id where d.name = 'Shrimp & Pork Soup Dumplings' and o.created_at >= '$START' and o.status not in ('CANCELLED','REJECTED')")
LEFT=$(q "select stock_remaining from dishes where name = 'Shrimp & Pork Soup Dumplings'")
echo "scarce dish: sold=$SOLD left=$LEFT of $LIMITED_STOCK (sold + left must equal $LIMITED_STOCK)"
echo "negative stock rows (must be 0): $(q "select count(*) from dishes where stock_remaining < 0")"
echo "ledger trial balance (must be 0): $(q "select coalesce(sum(amount_cents),0) from ledger_lines")"
echo "charged orders without exactly one capture (must be 0): $(q "select count(*) from orders o where o.status in ('PAID','ACCEPTED','READY','PICKED_UP','DELIVERED') and (select count(*) from ledger_entries e where e.kind='CAPTURE' and e.order_id=o.id) <> 1")"
echo "history gaps (versions not 1..n, must be 0): $(q "select count(*) from (select order_id from order_status_history group by order_id having count(*) <> max(version) or min(version) <> 1) x")"
echo "order row disagrees with its latest history entry (must be 0): $(q "select count(*) from orders o join lateral (select status, version from order_status_history h where h.order_id = o.id order by version desc limit 1) h on true where h.status <> o.status or h.version <> o.version")"
echo "provider confirm -> order PAID (server clocks, ms): $(q "select 'p50=' || round(percentile_cont(0.5) within group (order by ms)) || ' p95=' || round(percentile_cont(0.95) within group (order by ms)) || ' max=' || round(max(ms)) from (select extract(epoch from h.at - i.updated_at) * 1000 as ms from order_status_history h join payments p on p.order_id = h.order_id join dblink('dbname=mockpay user=wokflow', 'select id, updated_at from intents') as i(id text, updated_at timestamptz) on i.id = p.provider_intent_id where h.status = 'PAID' and h.at >= '$START') x")"
