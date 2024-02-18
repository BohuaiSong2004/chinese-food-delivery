-- 300,000 paid orders over the last 24 h for one restaurant (bench-1), two lines each, skewed so a few
-- dishes are much more popular. Benchmark data only: ledger lines are omitted, only CAPTURE entries.
create temporary table bench_orders as
select gen_random_uuid() as id, now() - ((g % 86000) * interval '1 second') as at, g
from generate_series(1, 300000) g;
insert into orders (id, customer_id, restaurant_id, status, subtotal_cents, delivery_fee_cents, commission_cents, total_cents,
                    delivery_address, idempotency_key, request_hash, payment_deadline, created_at, updated_at)
select b.id, (select id from users where email = 'customer@example.com'), (select id from restaurants where slug = 'bench-1'),
       'DELIVERED', 2000, 300, 300, 2300, 'bench', 'bench-' || b.g, 'bench', b.at, b.at, b.at
from bench_orders b;
with ids as (select array_agg(id order by id) a from dishes where restaurant_id = (select id from restaurants where slug = 'bench-1'))
insert into order_items (order_id, line_no, dish_id, name, name_zh, unit_price_cents, quantity)
select b.id, n, ids.a[1 + floor(100 * power(random(), 2))::int % 100], 'bench', '测试', 1000, 1 + (b.g % 2)
from bench_orders b, ids, generate_series(1, 2) n;
insert into ledger_entries (kind, ref, order_id, memo, created_at)
select 'CAPTURE', b.id::text, b.id, 'bench', b.at from bench_orders b;
analyze orders; analyze order_items; analyze ledger_entries;
