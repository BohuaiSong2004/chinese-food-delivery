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
7. **Everyone sees it**: the outbox publisher (woken by `LISTEN/NOTIFY`, claiming rows with
   `FOR UPDATE SKIP LOCKED`) publishes to a Redis channel; each replica forwards to its SSE clients; the
   client keeps the highest version it has seen.

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

## Things deliberately left out

- Access tokens are not revocable before they expire (15 minutes); that is the cost of stateless
  verification. Refresh tokens are revocable. Merchant restaurant scope in a token is likewise up to
  15 minutes stale.
- The hot-menu union is cached for 15 s, so "popular right now" can lag a paid order by up to 15 s.
- Redis pub/sub is at-most-once. Clients re-read the order on every (re)connect, and status is always
  confirmed from Postgres before anything acts on it.
- A real payment provider would also need 3-D Secure / SCA handling, partial refunds and disputes.
