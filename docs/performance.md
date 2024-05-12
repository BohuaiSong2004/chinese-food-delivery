# Performance

All numbers were measured on **2026-11-23** with the scripts in `loadtest/`. Raw k6 summaries are in
`loadtest/results/`.

## Environment and caveats

- One cloud VM with **2 vCPUs and 8 GB RAM**. The whole stack ran on it at once — Postgres, Redis,
  Elasticsearch (512 MB heap), two API replicas (`-Xmx384m` each), MockPay, nginx — **and the load
  generator**. Every component competes for the same two cores, so absolute throughput is far below what
  separate machines would give. Relative comparisons (A vs B on the same run day) are the meaningful part.
- The JVMs were warmed by one run before measuring; the cold run is reported separately.
- The stack was started with `RATE_LIMIT_ENABLED=false` (one machine plays every customer, so per-IP limits
  would only measure the limiter) and `SEED_LOADTEST_USERS=1200`.
- Not measured, and not claimed: anything on AWS (the Terraform was never applied), real-user behaviour, or
  the "+18% conversion" from the original resume line (that needs real traffic and an experiment).

## 1. 600 orders at the same instant (`peak.js`, `VUS=600`)

Each of 600 virtual customers places an order at the same moment, at one of the two 24-hour restaurants.
Half of them include the same scarce dish, reset to exactly **100 baskets** by `prepare.sh`; the other
limited dishes are plentiful but every order still decrements them. Each placed order is then paid on
MockPay (webhook → PAID) and moved through accept → ready → claim → pick up → deliver by the kitchen and two
riders, all concurrently. After every successful change the customer immediately reads the order back.
Clients behave like the web app: a `503` is retried after `Retry-After` with the same Idempotency-Key (or
the same `If-Match` version for status changes).

| Run (warm) | Placed + delivered | Sold out | Unexpected errors | 503 retries | Place p50 / p95 | Status change p50 / p95 | Payment → PAID p50 / p95 |
|---|---|---|---|---|---|---|---|
| 1 | 400 | 200 | 0 | 121 | 5.2 s / 14.8 s | 580 ms / 2.2 s | 519 ms / 2.5 s |
| 2 | 400 | 200 | 0 | 58 | 5.5 s / 14.2 s | 478 ms / 1.9 s | 276 ms / 1.2 s |
| 3 | 400 | 200 | 0 | 60 | 6.0 s / 13.6 s | 567 ms / 1.7 s | 313 ms / 1.0 s |

Checked after every run by `verify.sh` and the admin audit, identical in all three:

| Invariant | Result |
|---|---|
| Postgres deadlocks (`pg_stat_database`) | **0** |
| Scarce dish: sold + left = 100 | sold 100, left 0 — **0 oversold**, and the 200 refusals were exactly the surplus |
| Stale reads (read-after-write returned an older version) | **0** of 2,000 per run |
| Order row vs latest history entry | 0 disagreements; history versions gap-free |
| Ledger | trial balance 0, every charged order has exactly one capture, audit `consistent: true` |
| Outbox / stock holds left behind | 0 / 0 |

Place latency includes queueing: 600 requests arrive in the same millisecond on two shared cores and are
admitted 12 at a time per replica (the bulkhead), the rest wait. The **cold** run (fresh JVMs, before the
client retried status changes on 503) placed all 400 orders but left 89 status changes unretried; its
correctness checks were also all zero.

### Steady rush (`RATE=n`, 60 s, same mix)

| New orders / s | Orders placed | Place p50 / p95 | Status change p95 | Payment → PAID p50 / p95 | Errors |
|---|---|---|---|---|---|
| 5 | 250 | 16 ms / 28 ms | 10 ms | 31 ms / 53 ms | 0 |
| 10 | 400 | 13 ms / 22 ms | 10 ms | 32 ms / 53 ms | 0 |
| 15 | 550 | 12 ms / 29 ms | 12 ms | 29 ms / 53 ms | 0 |
| 25 | 850 | 15 ms / 71 ms | 45 ms | 32 ms / 63 ms | 0 |
| 40 | 1,300 | 23 ms / 816 ms | 551 ms | 46 ms / 277 ms | 0 |
| 60 | 1,900 | 1.9 s / 19.5 s | 2.5 s | 330 ms / 1.6 s | 0 (506 retries after 503) |

Each order costs about 10 requests over its life, so 15 orders/s is ≈150 requests/s. The knee on this box
is between 40 and 60 new orders per second; past it the system queues and sheds with `503 Retry-After`
rather than failing. "Orders placed" excludes the sold-out refusals. Deadlocks: 0 in every run.

## 2. Live order status (`live-watch.mjs`)

Each customer watches its own order over SSE (through nginx, so streams land on either replica) while the
order is driven to DELIVERED by requests that also land on either replica. Latency is browser-receive time
minus the order's `updated_at`.

| Streams | Events | p50 | p95 | p99 | Version regressions | Clients ending on DELIVERED |
|---|---|---|---|---|---|---|
| 5 (3 runs) | 30 each | 13–23 ms | 33–39 ms | 41–46 ms | 0 | all |
| 200 | 1,200 | 227 ms | 538 ms | 844 ms | 0 | 200 / 200 |

In the 200-stream run one event arrived after a newer one (the two replicas publish independently) and was
discarded by the client's version check — the designed behaviour; no client ever showed an older status.

## 3. Hot menu: Redis sorted sets vs SQL (`bench-hotmenu.sql`, `hotmenu_bench.py`)

One restaurant with **293,583 paid orders** (587k order lines) inside the 24-hour window, on a separate
compose project. Same question both ways: top 6 dishes over the window.

| Method | Median | p95 |
|---|---|---|
| SQL aggregation over ledger + orders + lines (as `HotMenuService.topFromSql`) | 246 ms | 284 ms |
| … with an extra `(kind, created_at)` index | 250 ms | 330 ms |
| Redis: `ZUNIONSTORE` of 24 hourly sets + `ZREVRANGE` (uncached) | **0.35 ms** | 0.58 ms |
| Redis: cached union (what most requests hit, 15 s TTL) | 0.06 ms | 0.10 ms |

Both returned the identical top 6. The index does not help because the cost is aggregating ~587k rows, not
finding them. The counters were built by the self-healing rebuild from the ledger (`hot:v1:ready` deleted).

## 4. Search: Elasticsearch vs the Postgres fallback (`search.js`, `search-accuracy.sh`)

20 clients for 30 s cycling through 16 real queries (30% with a tag filter); each request returns 20 hits
plus tag and cuisine facets from Elasticsearch.

| Catalog | Elasticsearch p50 / p95 | req/s | Postgres fallback p50 / p95 | req/s |
|---|---|---|---|---|
| 64 demo dishes | 87–116 ms / 164–261 ms | 152–212 | **30 ms** / 152 ms | 416 |
| 100,064 dishes (`bench-catalog.sql`) | **113–168 ms** / 203–370 ms | 105–166 | 2.76–2.87 s / 3.5–6.0 s | 7–8 |

On the tiny demo catalog Postgres is faster — it scans 64 rows while every Elasticsearch query runs a dozen
fuzzy, n-gram and pinyin clauses plus two aggregations on a shared CPU. At catalog scale the fallback's
`ILIKE '%…%'` / trigram plan falls over and Elasticsearch is ~20× faster.

Relevance matters more than speed here. Top-1 accuracy on 16 queries with one right answer (English,
typos, characters, spaced/joined pinyin, initials):

| | Elasticsearch | Postgres fallback |
|---|---|---|
| Correct | **15 / 16** | 10 / 16 |
| Misses | `xlb` (returned 虾仁鲜肉小笼包, another soup dumpling, instead of 鲜肉小笼包) | `mpdf`, `xlb`, `dandanmian`, `ddm`, `yupianzhou`, `roujiamo` — no pinyin at all |

## 5. Idle latency and front end

- Single requests with no load (k6, 1 client, 300 iterations): median 1.9 ms, p95 5.1 ms across order
  detail, cached menu, health and static files.
- Lighthouse 13.6, mobile emulation, against the nginx build:

| Page | Performance | Accessibility | Best practices | SEO | LCP | CLS |
|---|---|---|---|---|---|---|
| `/` | 100 | 100 | 100 | 100 | 0.8 s | 0 |
| `/search?q=noodles` | 100 | 100 | 100 | 100 | 0.8 s | 0 |
| `/signin`, `/cart` | 100 | 100 | 100 | 100 | 0.8 s | 0 |
| `/r/midnight-congee`, `/r/chengdu-night-market` | 93–94 | 100 | 100 | 100 | 3.1–3.2 s | 0 |

Restaurant pages lose a few points because their largest element is menu data, which needs the JavaScript
bundle and then one API round trip on throttled 4G.

## How the numbers moved while building

The peak test found most of the problems listed in [engineering-notes.md](engineering-notes.md). For the
600-order burst on the same machine:

| Change | Effect (warm runs) |
|---|---|
| First version | 123 of 600 placements failed with 500 (connection pool exhausted); payment → PAID 20 s median |
| Bulkhead + `503 Retry-After` | no 500s; 224 placements refused after a 10 s queue, 15 left without a checkout link |
| Stock reserved in single statements (no lock convoy) | all 600 resolved in warm runs, but some runs collapsed |
| nginx no longer counts the API's 503 as a dead replica | the collapses (hundreds of 502s) disappeared; 3 runs in a row with 0 errors |
| Clients retry status changes on 503 | 0 errors including status changes under the cold-ish load |
