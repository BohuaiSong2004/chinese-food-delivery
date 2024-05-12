# Engineering notes

How the main flows work, and the problems found while building and load-testing them — most were found by
the peak-hour load test, CI, or an accessibility/Lighthouse audit, not by reading the code.

## How an order moves

1. **Place** (`OrderService.place`). Prices come from Postgres, never the client. The request must carry an
   `Idempotency-Key`; the same key with the same body returns the original order (200), with a different
   body it is refused (422).
2. **Reserve stock** (`StockReservations`). For each limited dish, in ascending dish id order, one
   autocommit statement: `WITH taken AS (UPDATE dishes … WHERE stock_remaining >= :qty RETURNING id)
   INSERT INTO stock_holds … SELECT … FROM taken`. Either the decrement and its hold happen together or
   neither does.
3. **Create the order** in one transaction (order, lines, history v1, payment row, outbox event) that
   deletes the holds — they become the order. If it fails, the holds are released; if the process dies in
   between, a sweeper returns holds whose order never appeared.
4. **Pay**. After commit (no locks held) the API creates a payment intent at the provider, idempotent on the
   order id. The provider calls back with a signed webhook; de-duplication (`provider_events`), the
   `PENDING_PAYMENT → PAID` compare-and-set, the CAPTURE ledger entry and outbox events share one transaction.
5. **Kitchen and rider** actions are single-row compare-and-sets on `(status, version)`; the UI sends the
   version it showed as `If-Match`, so a stale screen gets 409 instead of acting twice.
6. **Cancel / reject** returns stock and, if the customer was charged, writes a refund request in the same
   transaction; a worker sends it to the provider with retries and books the REFUND entry on success.
7. **Everyone sees it**: the outbox publisher (woken in-process right after the commit, peers through a
   coalesced `NOTIFY`, claiming rows with `FOR UPDATE SKIP LOCKED`) appends each event to one bounded Redis
   Stream; every replica tails it and forwards to its SSE clients. A client that reconnects sends
   `Last-Event-ID` and the server replays what it missed from the stream (or sends a fresh snapshot if it
   was gone too long); the client keeps the highest version it has seen.
8. **Dispatch**: when the kitchen accepts, the nearest online rider (Redis GEO, positions older than 60 s
   ignored) gets a 30-second offer; declining or letting it expire moves it to the next rider, and the job
   board stays open as a fallback. Accepting an offer claims the order in the same transaction.

## Problems found and fixed

### 1. A lock convoy on the popular dish

*Found by:* the 600-order burst. 123 placements failed with 500 "could not get a connection", and Postgres
was only at ~38% CPU.

Half the orders contained the same dish. Stock was decremented inside the order transaction, so the row
lock was held while the application sent the remaining inserts and then `COMMIT`. A single commit takes
0.2 ms here (measured with pgbench), but with the API out of CPU the thread holding the lock waited tens of
milliseconds to be scheduled again, and every other order for that dish queued behind it, each holding a
pool connection while it waited.

*Fix:* reserve stock in one autocommit statement per dish (see step 2), so the lock never spans a round trip
to the application. Each statement locks one row and they run in ascending dish id order, so they still
cannot deadlock.

### 2. Pool starvation and backpressure

Even without the convoy, 600 simultaneous placements on virtual threads all reach for the connection pool
at once and starve webhooks and reads. A bulkhead (`Bulkhead`, 12 concurrent placements per replica) queues
the rest in memory; if a placement waits more than 10 s, or the pool still times out, the API answers
`503` with `Retry-After` instead of `500`. Clients retry with the same Idempotency-Key, so a retry can never
create a second order.

### 3. nginx turned backpressure into an outage

*Found by:* a warm run with 308 failures, all `502 Bad Gateway` — but no API replica had restarted.

nginx's `proxy_next_upstream … http_503` counted every deliberate 503 as a failed upstream; with
`max_fails=2` both replicas were marked down for 5 s at a time ("no live upstreams"), so the API saying
"busy, retry in 1 s" became nginx saying "nothing is up". Now only connection errors and timeouts count;
503s pass through to the client.

### 4. Two retries of one order reserved stock twice

*Found by:* CI (it never failed locally). Moving the reservation out of the order transaction removed the
protection the orders unique index had given duplicate requests: ten concurrent retries with the same
Idempotency-Key each passed the "already exists?" check and each reserved stock, so some were told
"sold out" although their order existed. Holds now record the request's customer and key under a unique
index: a duplicate's reservation statement fails whole (decrement included), and an out-of-stock answer
caused by the same request's own hold waits for the original order and returns it. The test repeats five
times per build.

### 5. Proving "zero deadlocks" means something

`LedgerIT.oppositeLockOrderDoesDeadlockAndIsCounted` locks two dish rows in opposite orders from two
connections: Postgres aborts one with `40P01` and `pg_stat_database.deadlocks` goes up by exactly one. So a
zero delta after the load tests is evidence, not an unread counter. If a deadlock ever did happen, `Tx`
retries the victim and counts it (`wokflow.tx.retries`).

### 6. Webhooks: a flood that the de-duplication absorbed

MockPay's first delivery loop collected results with `Map.entry(delivery, error)`; for a success the error
is `null`, `Map.entry` throws, the row was never marked delivered, and every webhook was re-sent every
100 ms. The API's exactly-once handling held — each order was captured once and the ledger stayed balanced
— which is the point of de-duplicating by event id, but it was still a bug. Two more MockPay problems
followed: sending a batch with `parallelStream` from inside the transaction that held the rows' locks (the
updates from other threads would have waited on that same transaction), and a batch barrier that made one
slow response hold up 49 others. Deliveries are now claimed with a short lease in one `UPDATE … RETURNING`,
sent concurrently on virtual threads, and recorded independently.

### 7. Elasticsearch: a builder call that replaced the mapping

To store a mapping hash in the index's `_meta`, the first version called `.mappings(m -> m.meta(…))` on the
create request after `.withJson(file)`. That replaced the file's mappings entirely: `cuisine` became a text
field and every facet aggregation failed. Search kept working because failed queries degrade to Postgres,
which is also how it was noticed (`degraded: true` in the tests). The hash now goes into the JSON itself.
The hash exists so that a changed mapping triggers a rebuild on deploy: previously the index was rebuilt
only when missing or when its count disagreed with Postgres, so new fields would have stayed empty forever.

### 8. A second replica ignored the new index for a minute

When two replicas start, one builds the index and the other skips ("another replica is rebuilding"). The
skipper then waited for the next scheduled check, a minute later, and served degraded Postgres search until
then — which made the e2e search test fail in CI when the request landed on that replica. The startup
thread now re-checks every 2 s until the alias exists.

### 9. Cache invalidation race on the menu

Deleting the cached menu after an edit is not enough: a reader that loaded the old menu before the edit
committed can write it back after the delete. The cache key now contains `restaurants.menu_version`, which
every menu change increments in the same transaction, so an old copy is simply never read again.

### 10. Auth details found by testing

- The per-account sign-in limit counted successful sign-ins, so the e2e suite locked out the demo merchant.
  Per account, only wrong passwords count now (`RateLimiter.peek` / `record`); per IP, all attempts count.
- A signed-out page load called `/api/auth/refresh` and got 401, which browsers log as a console error
  (Lighthouse best practices 96). No cookie is the normal signed-out state and now returns 204.
- The sliding-window limiter is an estimate (current window + previous window × overlap). A test first
  claimed it allows nothing extra across a boundary; it actually allows at most one more, versus a full
  second burst for a fixed window. The test now asserts the real bound.
- 20 sign-ups per IP per hour was too strict for a shared campus or office NAT (three browsers' e2e runs
  hit it); it is 60.

### 11. Smaller ones

- Outbox loops on virtual threads: `Object.wait()` and a blocking JDBC read pin the carrier thread on Java
  21, and a 2-core box has two carriers. They run on platform threads.
- The local Elasticsearch refused to allocate shards because the disk was above the 90% watermark; the
  compose file disables the disk threshold for the single local node.
- axe found `label-content-name-mismatch` (WCAG 2.5.3): the language button's accessible name did not
  contain its visible text "中文".
- The page footer shifted down when menu data arrived (CLS 0.109); `main` is now at least a screen tall.
- `Outbox.publish` refuses to run outside a transaction, so an event can never be published for a change
  that then rolls back (`OutboxIT`).

### 12. Every commit waited on the outbox NOTIFY

At 25 orders/s the API was slow while neither JVM was busy (Flight Recorder: ~10% CPU, the time spent in
socket reads from Postgres). Sampling `pg_stat_activity` showed 37 of 42 active backends in
`Lock / object` on `COMMIT`. The outbox insert trigger ran `pg_notify` inside every business transaction,
and Postgres holds a cluster-wide lock from a transaction's NOTIFY until its commit is flushed, so every
order, payment and status change committed one at a time. The trigger is gone: after commit the API wakes
its own publisher in-process (it can deliver the row itself, the claim is `SKIP LOCKED`) and sends one
coalesced `NOTIFY` per 50 ms for the other replicas, outside any business transaction. At 40 orders/s,
placement p95 went from 8.9 s to 4.5 s and the 503 retries from 35 to 0. What remained were `WALWrite`
waits; `pg_test_fsync` showed 124 µs per sync, so those were CPU starvation on a two-core box.

### 13. A committed change answered with 503

The 600-order burst left one to four "unexpected" 409s per run, always the same shape: a kitchen
"ready" refused because the order was *already* READY at the next version. The status change committed,
then the API loaded the response on a second pooled connection; with the pool exhausted that failed with
503, the client retried with the same `If-Match`, and the retry lost to its own first attempt. The response
is now built in the same transaction, and a retry whose expected version was followed by exactly this
actor's same change returns the current order (a different action, or another rider's claim, is still a
409). The same idea made order pages one read-only transaction instead of one pool borrow per query
(time holding a placement slot under the burst: p50 0.28 → 0.12 s, p95 4.6 → 2.2 s).

### 14. The live stream silently skipped events

Moving live updates from pub/sub to a Redis Stream (for replay on reconnect) lost events in the first
test. `ReadOffset.latest()` re-reads from `$` on every poll, so anything appended between two polls was
never seen; `lastConsumed()` starts at `$` once and continues from the last id. The second bug: the poll
blocked for 2 s, exactly the client's command timeout, so every idle poll was an error and the default
error policy cancelled the subscription. The poll is 1 s and errors no longer cancel it.

### 15. A test helper deadlocked the suite

The full suite hung at random. The helper that gave each test customer an address did an HTTP call inside
`ConcurrentHashMap.computeIfAbsent`; on Java 21 a virtual thread blocked inside that `synchronized` section
pins its carrier, and the request handlers it was waiting for needed the same few carriers. It uses a
per-token `ReentrantLock` now.

### 16. Freezing each dependency (FaultIT)

`docker pause` keeps a port open while nothing answers, so every client falls back on its timeouts. Five
problems showed up:

- Search waited out the Elasticsearch client's 10 s socket timeout on every query. It now gets a 1.5 s
  budget and a 10 s breaker (which closes early if the abandoned query completes); an error *returned* by
  Elasticsearch, such as a missing index during a rebuild, only affects that request.
- With Redis frozen, one menu page made three optional Redis calls and waited 2 s for each. A breaker skips
  optional Redis work for 5 s after a failure: first page 2.1 s, then ~15 ms.
- The outbox retried a failing topic for every queued row, so search indexing waited behind live events
  that could not be sent. A failing topic is now skipped for the rest of the batch, Redis-bound topics are
  held while Redis is down, and held events do not grow their backoff (a dish change reached the index in
  1.8 s while 20 live events waited, instead of 7.7 s).
- A frozen Postgres held requests forever: there was no JDBC socket timeout. Now 10 s, a 503 with
  `Retry-After`, and a readiness probe that includes the database (liveness does not, to avoid restart
  loops).
- Open SSE streams kept Tomcat's graceful shutdown waiting until Docker killed the container at 10 s. The
  API now closes every stream when shutdown starts; clients reconnect to the other replica and resume from
  `Last-Event-ID`. Stopping a replica with 10 open streams: 0.7 s.

### 17. Verifying the same token over and over

In the load window of a Flight Recorder profile, about a quarter of the API's samples were ES256
signature verification, for tokens each client reuses for 15 minutes. Tokens that pass every check are
now cached until their own expiry (invalid ones never are). Authenticated requests: 556 → 1,088 req/s,
API CPU per request 2.6 → 1.0 ms.

### 18. Comparing against yesterday's numbers

Re-running the steady test, placement at 25 orders/s was p50 4.5 s against a documented 15 ms. Before
hunting a regression, the commit the old numbers came from was built and run on the same VM: it reproduced
them (14 ms / 71 ms). But it had carried 584 orders through their whole life against today's 850, because
extra dishes sold out in its seed data, so it was a lighter test. Per delivered order the two builds
cost the same: Postgres 31 vs 29 ms of CPU, about 160 statements each (`pg_stat_statements`), and less API
CPU in the new one. Two lessons kept: measure work per unit and not only latency, and burst runs on this
box vary by ±50% on identical code, so a single run proves nothing. (Building the old commit also
overwrote the current images, because compose pins their tags; the next start failed in the seeder until
they were rebuilt.)

### 19. The NOTIFY fix put live events out of order

Re-measuring after story 12, the 200-stream test received 982 of 1,200 events and ignored 109 as
older than what the client had already shown: a later status had overtaken an earlier one, and when
DELIVERED overtook PICKED UP the client stopped before the older one arrived. Reading the Redis Stream
back showed 62 inversions in one run. Since story 12 each replica publishes its own commits at once, so
an order's ACCEPTED (committed on one replica) and READY (on the other, milliseconds later) were published
concurrently. The first fix, "claim an event only if no earlier event of its order is unpublished",
was correct but forced each order's events into separate batches (p50 2.7 s). The version kept: a
batch handles an order's events in id order, but only up to the first pending event of that order that
is not in the batch (another replica holds it, or it waits for a retry). The first attempt at that missed
a gap: another replica could hold v4 while this batch had v3 and v5. Two `OutboxIT` tests pin both cases
(each fails without the fix), and the stream now reads back with 0 inversions in 1,200 events. With every
event now going through one publisher per order, Redis round trips became the cost, so a batch's stream
appends are pipelined: 200-stream p50 2.1 s → 0.8–1.3 s. When an order's steps are spaced like a real
kitchen's (0.5 s apart), 5 streams see p50 25–45 ms.

### 20. More small ones from this round

- The password-reset e2e passed locally and failed in CI: on a cold cache the sign-in form stayed on screen
  while the next page's chunk loaded, so the test typed the email into the old form. It waits for the new
  heading first.
- `(0.95).toFixed(1)` is `"0.9"` (0.95 is stored as 0.9499…), so 950 m was shown as "0.9 km".
- A test of restocking used the UTC date as the restaurant's "yesterday" and failed in CI late in the
  Pacific evening.
- `List.of(double[][])` spread a polygon's rows as varargs and Elasticsearch rejected the shape.
- An address form nested inside the checkout form made the browser submit natively (405).
- nginx `add_header` in a `location` replaces the server's headers, so the page lost its
  `Permissions-Policy`.
- Search tests looked for "fried rice" and "xlb", which every test kitchen sells; in a full run the
  expected dish could fall off the first page.

### 21. Search features that halved search throughput

Synonyms, a relaxed second pass, highlighting and "N dishes without the filters" all passed their tests.
Re-running the 20-client search benchmark against the previous build, alternated on the same machine
(this VM varies ±50%, so only back-to-back runs count), showed **45–55 req/s against 136–146**, p95 4×
worse. Single requests were still 7–46 ms, so the cost only showed under concurrency.

`docker stats` during the run put Elasticsearch at 88% CPU and each API replica at ~45%: the queries had
become heavier. Switching highlighting off in an experiment brought ~108 req/s back. The highlighter had
`require_field_match: false`, so it re-applied every fuzzy, prefix and n-gram clause of the query (most of
them aimed at hidden pinyin fields) to every highlighted field of every hit. A small highlight query of its
own helped (~66 req/s) but not enough. The fix was to stop asking Elasticsearch: `Highlighter` marks the
words in Java on the 20 hits being returned (word starts, Elasticsearch's AUTO edit distance, adjacent
Chinese character pairs), which is microseconds, and the Postgres fallback gets the same marks.

Two more pieces of the gap: filtered searches that found nothing made a second request to count the
matches without the filters, now a `post_filter` plus an aggregation in the same request; and each synonym
variant repeated the full fuzzy, multi-field, pinyin clause set, now exact words only (the variant comes
from our list, not from someone's typing). End result 120–123 req/s against 136–146: the remaining
~12–15% is real extra work. Suggestions went from p95 274 ms to 49 ms with the same change. The lesson is
the same as story 18: every feature that touches a hot path gets a back-to-back benchmark against the
previous build, not just a functional test.

### 22. 47 out of 47 (and why that number is not in the README)

The synonym list was written by running 47 labeled queries, looking at what failed and adding the missing
names. Afterwards the same 47 scored 47/47 — but that measures how well the list was fitted to those
queries, not how well it generalizes. So a second set of 45 queries was written before running them, and
`synonyms.txt` was frozen: held-out top-1 went 35 → 37 with synonyms alone. Most remaining misses returned
nothing at all because every word had to match ("spicy fish": no dish name says "spicy"), which led to the
relaxed second pass — 37 → 41, later 40 after the synonym clauses were made cheaper (story 21). The README
quotes the held-out numbers and says the relaxed pass was designed after seeing those misses; the 47/47 is
only in `docs/performance.md`, labeled as the tuning set.

## Things deliberately left out

- Access tokens are not revocable before they expire (15 minutes); that is the cost of stateless
  verification. Refresh tokens are revocable. Merchant restaurant scope in a token is likewise up to
  15 minutes stale.
- The hot-menu union is cached for 15 s, so "popular right now" can lag a paid order by up to 15 s.
- The live stream keeps about 20,000 events. A client away longer than that gets a snapshot (or "resync"
  for list views) instead of a replay; status is always confirmed from Postgres before anything acts on it.
- No distributed tracing: one service plus its stores, and the per-route histograms show the slow paths.
- Recommendations are co-occurrence counts, not a trained model: with no real users there is nothing to
  train on or evaluate against, so the cosine ranking is not claimed to be better than anything — only to
  stop the most popular dish from being suggested with everything.
- The synonym list is hand-written (104 phrases). The admin page lists searches that matched nothing, which
  is where new entries should come from; nothing adds them automatically.
- A real payment provider would also need 3-D Secure / SCA handling, partial refunds and disputes.
