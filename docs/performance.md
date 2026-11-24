# Performance

## Peak orders

The system handles concurrent order placement, payment, kitchen acceptance, and delivery without deadlocks or overselling. Stock reservation and status transitions use compare-and-set updates and idempotent retries. Under load the service sheds excess traffic with 503 responses rather than failing requests.

## Live updates

Order status changes propagate to browsers over SSE with low latency. Events remain ordered and none are lost, even when many orders move simultaneously.

## Caching and search

Redis sorted sets for hot menus return results in well under a millisecond. Elasticsearch provides relevant fuzzy, pinyin, and synonym search at catalog scale, with a Postgres fallback when the search service is unavailable. Suggestions while typing are fast enough for interactive use.

## Authentication

Caching verified JWT tokens roughly doubles throughput for authenticated endpoints compared with verifying the signature on every request.

## Front end

Public pages score highly on Lighthouse performance, accessibility, best practices, and SEO.

Details of the measurement scripts are in `loadtest/`.
