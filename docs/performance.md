# Performance

All numbers were measured on **2026-11-24** against the final build, with the scripts in `loadtest/`
(raw k6 summaries in `loadtest/results/`), except where a section says otherwise. A table of the first
version, measured on 2026-11-23, was replaced; see "Comparing days" at the end.

## Environment and caveats

- One cloud VM with **2 vCPUs and 8 GB RAM**. The whole stack ran on it at once — Postgres, Redis,
  Elasticsearch (512 MB heap), two API replicas (`-Xmx384m` each), MockPay, nginx, Mailpit — **and the load
  generator**. Every component competes for the same two cores, so absolute throughput is far below what
  separate machines would give. Relative comparisons on the same day are the meaningful part.
- Runs on this box vary a lot: the 600-order burst gave placement p50 between 3.3 and 6.3 s on identical
  code. Single runs prove little; every table shows each run or a range.
- The JVMs were warmed by one run before measuring; the cold run is reported separately. Monitoring
  (Prometheus, Grafana) was stopped.
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

| Run | Placed + delivered | Sold out | Unexpected responses | 503 retries | Place p50 / p95 | Status change p50 / p95 | Payment → PAID p50 / p95 |
|---|---|---|---|---|---|---|---|
| cold | 400 | 200 | 0 | 498 | 13.2 s / 36.9 s | 620 ms / 6.4 s | — |
| warm 1 | 400 | 200 | 0 | 173 | 4.8 s / 19.7 s | 707 ms / 2.2 s | 1.5 s / 6.5 s |
| warm 2 | 400 | 200 | 0 | 0 | 4.4 s / 13.5 s | 708 ms / 2.2 s | 1.2 s / 2.9 s |
| warm 3 | 400 | 200 | 0 | 0 | 3.8 s / 5.0 s | 641 ms / 1.7 s | 3.9 s / 6.6 s |

Checked after every run by `verify.sh`, identical in all of them:

| Invariant | Result |
|---|---|
| Postgres deadlocks (`pg_stat_database`) | **0** |
| Scarce dish: sold + left = 100 | sold 100, left 0 — **0 oversold**, and the 200 refusals were exactly the surplus |
| Stale reads (read-after-write returned an older version) | **0** of 2,000 per run |
| Order row vs latest history entry | 0 disagreements; history versions gap-free |
| Ledger | trial balance 0, every charged order has exactly one capture |
| Outbox left behind | 0 |

Place latency is mostly queueing: 600 requests arrive in the same millisecond on two shared cores and are
admitted 12 at a time per replica (the bulkhead). Its timers split the wait: in a warm burst on the
build before the read-only-transaction change, time queued for a slot was p50 6.7 s while the work itself
held the slot p50 0.28 s (0.12 s after that change). Payment → PAID is slow
during the burst for the same reason (the webhooks wait for the CPU like everything else); at a steady
15 orders/s it is p50 35 ms / p95 67 ms.

### Steady rush (`RATE=n`, 60 s, same mix)

| New orders / s | Orders placed | Place p50 / p95 | Status change p50 / p95 | Errors |
|---|---|---|---|---|
| 5 | 250 | 19 ms / 34 ms | 6 ms / 14 ms | 0 |
| 10 | 400 | 18 ms / 41 ms | 6 ms / 17 ms | 0 |
| 15 | 550 | 24 ms / 166 ms | 9 ms / 81 ms | 0 |
| 25 | 850 | 28 ms / 492 ms | 16 ms / 261 ms | 0 |
| 40 | 1,300 | 276 ms / 1.8 s | 164 ms / 760 ms | 0 |
| 60 | 1,900 | 3.0 s / 24.4 s | 487 ms / 1.8 s | 0 (870 retries after 503) |

Each order costs about 10 requests over its life, so 15 orders/s is ≈150 requests/s. The knee on this box
is between 25 and 40 new orders per second; past it the system queues and sheds with `503 Retry-After`
rather than failing. In an earlier run of the previous build at 60 orders/s, 9 requests waited past k6's
60 s client timeout (no server errors). "Orders placed" excludes the sold-out refusals. Deadlocks: 0.

## 2. Live order status (`live-watch.mjs`)

Each customer watches its own order over SSE (through nginx, so streams land on either replica) while the
order is driven to DELIVERED by requests that also land on either replica. Latency is browser-receive time
minus the order's `updated_at` (the start of the transaction that changed it, so it includes that
transaction).

| Streams | Steps | Events received | p50 | p95 | Out of order | Clients ending on DELIVERED |
|---|---|---|---|---|---|---|
| 5 (3 runs) | 0.5 s apart (`PAUSE_MS=500`) | 30 / 30 | 25–45 ms | 56–147 ms | 0 | all |
| 5 (3 runs) | back to back | 30 / 30 | 118–250 ms | 275–576 ms | 0 | all |
| 200 (2 runs) | back to back | 1,200 / 1,200 | 0.8–1.3 s | 2.0–3.5 s | 0 | 200 / 200 |

Every event of an order reaches the stream in order (checked by reading the Redis Stream back: 0
inversions in 1,200 events). Back-to-back steps are harsher than real use: each of an order's events waits
for the previous one to be published. (Engineering note 19 has the story: a faster version delivered
events out of order.)

## 3. Hot menu: Redis sorted sets vs SQL (`bench-hotmenu.sql`, `hotmenu_bench.py`)

Measured 2026-11-23 (this code has not changed since). One restaurant with **293,583 paid orders** (587k
order lines) inside the 24-hour window, on a separate compose project. Same question both ways: top 6
dishes over the window.

| Method | Median | p95 |
|---|---|---|
| SQL aggregation over ledger + orders + lines (as `HotMenuService.topFromSql`) | 246 ms | 284 ms |
| … with an extra `(kind, created_at)` index | 250 ms | 330 ms |
| Redis: `ZUNIONSTORE` of 24 hourly sets + `ZREVRANGE` (uncached) | **0.35 ms** | 0.58 ms |
| Redis: cached union (what most requests hit, 15 s TTL) | 0.06 ms | 0.10 ms |

Both returned the identical top 6. The index does not help because the cost is aggregating ~587k rows, not
finding them.

## 4. Search: Elasticsearch vs the Postgres fallback (`search.js`, `search-accuracy.sh`)

20 clients for 30 s cycling through 16 real queries; each request returns 20 hits plus tag and cuisine
facets from Elasticsearch.

| Catalog | Elasticsearch p50 / p95 | req/s | Postgres fallback p50 / p95 | req/s |
|---|---|---|---|---|
| 64 demo dishes (2026-11-24) | 185 ms / 439 ms | 93 | **20 ms** / 69 ms | 748 |
| 100,064 dishes (2026-11-23, `bench-catalog.sql`) | **113–168 ms** / 203–370 ms | 105–166 | 2.76–2.87 s / 3.5–6.0 s | 7–8 |

On the tiny demo catalog Postgres is faster — it scans 64 rows while every Elasticsearch query runs a dozen
fuzzy, n-gram and pinyin clauses, a geo filter, two aggregations and a rating function on a shared CPU. At
catalog scale the fallback's `ILIKE '%…%'` / trigram plan falls over and Elasticsearch is ~20× faster.

Relevance matters more than speed here. Top-1 accuracy on 16 queries with one right answer (English,
typos, characters, spaced/joined pinyin, initials), re-checked 2026-11-24:

| | Elasticsearch | Postgres fallback |
|---|---|---|
| Correct | **15 / 16** | 10 / 16 |
| Misses | `xlb` (another soup dumpling ranks first) | `mpdf`, `xlb`, `dandanmian`, `ddm`, `yupianzhou`, `roujiamo` — no pinyin at all |

## 5. Authenticated requests: verified-token cache

50 clients for 30 s, each reusing its own access token on `GET /api/me/addresses` (a cheap read), same
stack, before and after caching verified tokens:

| | req/s | p50 | API CPU per request |
|---|---|---|---|
| Verifying the ES256 signature every time | 556 | 59 ms | 2.6 ms |
| Verified tokens cached until they expire | **1,088** | 29 ms | 1.0 ms |

## 6. Front end

Lighthouse 13.6, mobile emulation (default throttling), against the nginx build, median of 3 runs:

| Page | Performance | Accessibility | Best practices | SEO | LCP | CLS |
|---|---|---|---|---|---|---|
| `/` | 98 | 100 | 100 | 100 | 0.8 s | 0 |
| `/search?q=noodles` | 99 | 100 | 100 | 100 | 0.8 s | 0 |
| `/signin` | 98 | 100 | 100 | 100 | 0.8 s | 0 |
| `/cart` | 95 | 100 | 100 | 100 | 0.8 s | 0 |
| `/r/midnight-congee`, `/r/chengdu-night-market` | 85–86 | 100 | 100 | 100 | 4.0–4.1 s | 0.018 |

Restaurant pages lose points because their largest element is now the cover photo, which can only be
requested after the JavaScript bundle and the menu API call, on throttled 4G (before photos existed the
menu text was the LCP element and the score was 93–94). Individual runs vary by ±5 points.

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
| Status-change response built inside its transaction; retries of one's own change succeed | the 1–4 "unexpected" 409s per burst (a committed change answered with 503) went to 0 |
| Order pages read in one read-only transaction | time holding a placement slot p50 0.28 → 0.12 s, p95 4.6 → 2.2 s |
| No `NOTIFY` inside business transactions | at 40 orders/s: placement p95 8.9 → 4.5 s, 503 retries 35 → 0 |
| Per-order event order across replicas, pipelined stream appends | 200 streams: 0 out-of-order events (was 109 ignored); p50 2.1 s → 0.8–1.3 s |
| Verified-token cache | authenticated reads 556 → 1,088 req/s |
| Home photos lazy, fixed-size trending slot | home Lighthouse LCP 4.3 s (some runs) → 0.8 s, CLS 0.08–0.14 → 0 |

## Comparing days

The 2026-11-23 report had the first version at 25 orders/s with placement p50 15 ms / p95 71 ms, lower than
the 28 ms / 492 ms above. Re-running that exact commit on 2026-11-24 reproduced its numbers, but it had
carried 584 orders through their whole life against today's 850 at the same arrival rate (extra dishes sold
out in its seed data), so it was a lighter test. Per delivered order the two builds cost the same database
CPU (31 vs 29 ms) and statement count (~160), and the new one less API CPU. Engineering note 18 has the
details.
