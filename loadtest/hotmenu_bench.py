"""Hot-menu read: SQL aggregation over the ledger vs the Redis hourly sorted sets.

Same question both ways: the top 6 dishes of one restaurant over the 24-hour window.
Usage: python3 loadtest/hotmenu_bench.py <postgres-host> <redis-host> <restaurant-slug>
"""
import statistics
import sys
import time

import psycopg
import redis

PG, RD, SLUG = sys.argv[1], sys.argv[2], sys.argv[3]
TOP = """
if redis.call('EXISTS', KEYS[1]) == 0 then
  local src = {}
  for i = 2, #KEYS do src[#src + 1] = KEYS[i] end
  redis.call('ZUNIONSTORE', KEYS[1], #src, unpack(src))
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return redis.call('ZREVRANGE', KEYS[1], 0, tonumber(ARGV[2]) - 1, 'WITHSCORES')
"""  # identical to HotMenuService.TOP

def timed(fn, n):
    out, ms = None, []
    for _ in range(n):
        t = time.perf_counter()
        out = fn()
        ms.append((time.perf_counter() - t) * 1000)
    return out, ms

def summary(ms):
    ms = sorted(ms)
    return f"median {statistics.median(ms):.2f} ms, p95 {ms[int(len(ms) * 0.95) - 1]:.2f} ms (n={len(ms)})"

with psycopg.connect(f"host={PG} dbname=wokflow user=wokflow password=wokflow") as db:
    rid = db.execute("select id from restaurants where slug = %s", (SLUG,)).fetchone()[0]
    hour = int(time.time()) // 3600
    since = (hour - 23) * 3600
    sql = """
        select i.dish_id, sum(i.quantity) from ledger_entries e
        join orders o on o.id = e.order_id join order_items i on i.order_id = o.id
        where e.kind = 'CAPTURE' and o.restaurant_id = %s and e.created_at >= to_timestamp(%s)
        group by i.dish_id order by 2 desc, 1 limit 6"""  # identical to HotMenuService.topFromSql
    rows = db.execute("select count(*) from ledger_entries e join orders o on o.id = e.order_id "
                      "where e.kind = 'CAPTURE' and o.restaurant_id = %s and e.created_at >= to_timestamp(%s)", (rid, since)).fetchone()[0]
    db.execute(sql, (rid, since)).fetchall()  # warm the cache
    sql_top, sql_ms = timed(lambda: db.execute(sql, (rid, since)).fetchall(), 30)

r = redis.Redis(host=RD, decode_responses=True)
script = r.register_script(TOP)
prefix = f"hot:v1:r:{rid}:"
buckets = [prefix + str(hour - i) for i in range(24)]
counter = iter(range(10**9))
_, uncached_ms = timed(lambda: script(keys=[f"bench:top:{next(counter)}"] + buckets, args=[1, 6]), 200)
redis_top, cached_ms = timed(lambda: script(keys=["bench:top:cached"] + buckets, args=[60000, 6]), 200)

sql_pairs = [(int(d), int(q)) for d, q in sql_top]
redis_pairs = [(int(redis_top[i]), int(float(redis_top[i + 1]))) for i in range(0, len(redis_top), 2)]
print(f"orders in window: {rows}")
print(f"SQL aggregation      : {summary(sql_ms)}")
print(f"Redis ZUNIONSTORE x24: {summary(uncached_ms)}")
print(f"Redis cached result  : {summary(cached_ms)}")
print(f"same top 6: {sql_pairs == redis_pairs}  sql={sql_pairs[:3]}...  redis={redis_pairs[:3]}...")
