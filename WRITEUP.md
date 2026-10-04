# Write-up

## 1. The atomic decision

**Mechanism:** a pessimistic row lock on the seat rows, taken in sorted order, followed by a state check under that lock, all inside one database transaction (`SeatService.doReserve`).

```sql
SELECT label, status FROM seats
WHERE show_id = ? AND label = ANY(?)
ORDER BY label
FOR UPDATE;
-- every row must exist and be 'available', otherwise decline with 409 (nothing written)
INSERT INTO reservations ...;
UPDATE seats SET status='confirmed', user_id=?, reservation_id=?
WHERE show_id=? AND label = ANY(?) AND status='available';
```

**Why it is race-free.** There is no gap between "is it free?" and "take it", because the read is the lock. Suppose 500 requests race for seat A12. Postgres lets one transaction take the row lock and the other 499 wait on it. The winner updates the row to `confirmed` and commits. Under READ COMMITTED each waiter then re-reads the now-committed row, sees `confirmed`, and throws a 409 `seat_taken`. Exactly one request can ever move a seat from `available` to `confirmed`. The final `UPDATE` repeats the `status='available'` guard and its row count is checked, so if the state ever changed, the transaction aborts instead of selling twice. The primary key `(show_id, label)` makes a duplicate seat row impossible.

**Multi-seat requests are all-or-nothing.** The decision happens for the whole set under the locks. If any seat is unavailable, the request is declined with 409 and nothing is written, so no half-bookings exist at any moment, even under concurrency. I chose all-or-nothing over best-effort because a buyer who asks for seats together (A12 and A13) usually does not want only one of them, and it keeps the response and the idempotent replay unambiguous.

**Avoiding deadlock.** Seat labels are validated, de-duplicated and sorted before locking, and the `ORDER BY label` makes Postgres lock them in that order. Two overlapping multi-seat requests therefore always acquire locks in the same order and cannot form a cycle. As a safety net, `withRetry` retries up to 3 times if Postgres still reports a deadlock or lock failure; this is safe because the aborted transaction committed nothing. Cancellation locks the reservation row and then its seats, while reservation inserts create a new row, so there is no opposite-order cycle between the two.

**Zero 5xx.** Every decline is an `ApiException` with a 4xx status, mapped by `ApiHandler`. Tomcat has a large accept queue and Hikari a long connection timeout, so a burst queues instead of failing. The only 5xx paths are real faults (e.g. database down, which returns 503).

## 2. Idempotency

* **Where the key lives:** `reservations(user_id, idempotency_key)` with a `UNIQUE` constraint, plus a `request_hash` column (show id plus the sorted seat list). The key is read from the `Idempotency-Key` header, falling back to the body field.
* **How exactly-once is enforced:** at the start of each reservation transaction I take `pg_advisory_xact_lock(hashtextextended(user_id))`, which serialises that user's requests (released automatically at commit or rollback). Under the lock the service looks up `(user_id, key)`. If a row exists, it is returned and nothing is written; otherwise the new reservation is inserted. Two parallel retries of the same request therefore cannot both insert, and the unique constraint is a backstop if the lock were ever bypassed.
* **Same key, same body:** returns the original reservation with **200** and `Idempotent-Replay: true` (a replay does not move any seat and is counted as `idempotent_replay`). I return 200 rather than 201 so that "exactly one 201 per seat" stays true even when clients retry.
* **Same key, different body:** the stored `request_hash` does not match, so the response is **409 `idempotency_conflict`**.
* **Scope and lifetime:** keys are scoped per user, so two users may use the same string. They are kept for the life of the reservation record.
* **Declines are not stored.** A request that was declined (`seat_taken`, `per_user_limit`) wrote nothing, so retrying it with the same key is evaluated again. That is intentional: a seat that was taken may free up, and the retry may legitimately succeed.
* **Lost responses:** if the commit succeeds but the client never sees the response, the retry with the same key replays the original. If the transaction did not commit, nothing exists and the retry runs normally.

**Per-user limit.** The same advisory lock protects the limit. Under it, the service counts the user's `held` and `confirmed` seats in the show and rejects with 409 `per_user_limit` if the count plus the request exceeds the limit. Ten parallel reserves from one user are serialised, so the user ends with at most 4 seats on a limit-4 show.

**Identity.** Every request's identity comes from the HMAC-signed bearer token verified in `RequestFilter`. The controller never reads a `user_id` from the body, so a spoofed field cannot change who acts. `cancel` compares the token user to the reservation owner and returns 403 otherwise.

## 3. Holds and expiry

I chose the **explicit-cancel model**: a reservation is `confirmed` immediately and the owner can `POST /reservations/{id}/cancel`.

* Cancel locks the reservation row (`FOR UPDATE`), checks ownership, then runs `UPDATE seats SET status='available', user_id=NULL, reservation_id=NULL WHERE reservation_id=? AND status='confirmed'`. The `reservation_id` guard means a cancel can never affect a seat that now belongs to someone else, so it cannot resurrect a seat confirmed to another user.
* A second cancel is a no-op that returns 200 (idempotent), and the cancelled counter only increments on the first one.
* Once released, the seat is `available` and can be reserved again immediately.
* The `held` status exists in the schema and in the invariant (`available + held + confirmed == total_seats`) but is unused, so `held` is always 0.

**How I would add time-boxed holds:** add `held_until` to `seats`; a `hold` call sets `status='held'` and `held_until = now() + interval '5 minutes'` using the same locked, sorted check, treating a seat as bookable if it is `available` or `held` with an expired `held_until` (lazy expiry inside the same locked statement, so there is no race with a sweeper); a `confirm` call updates `WHERE status='held' AND held_until > now() AND reservation_id=?`; a scheduled sweeper only tidies up. The per-user limit already counts `held`.

## 4. Consistency versus availability

PostgreSQL is a single writer and the system of record, so I choose **consistency over availability**.

* If the database is unreachable, `/readyz` returns 503 (it really pings the database, with a 2-second budget) and reserve and cancel calls return 503. The service never answers from a cache and never guesses whether a seat is free, because a wrong "yes" would sell a seat twice.
* `/healthz` deliberately does not touch the database, so a database outage makes the instance *not ready* rather than getting it restarted in a loop.
* A transaction is atomic, so a connection lost mid-request leaves either everything or nothing. Because of the idempotency key, a client can safely retry after any ambiguous failure.
* Scaling out the app is safe, since correctness lives in the database and not in app memory. Scaling the database is the bottleneck: the hot-seat path serialises on one row, which is the correct behaviour for a unique resource. Read traffic (`GET /shows/{id}`) could move to a replica and tolerate a little staleness, but reservations must stay on the primary.

## 5. Observability: what I would be paged for at 2am

Signals exposed: `reservations_confirmed_total`, `reservations_declined_total{reason}`, `reservations_cancelled_total`, `seats_available{show_id}`, Spring's `http_server_requests_seconds_*` (status and latency), Hikari pool metrics, and JVM metrics. Logs are JSON with `request_id`, `user_id`, `status`, `duration_ms`.

Page-worthy:

1. **Any 5xx on the reserve endpoint**, e.g. `increase(http_server_requests_seconds_count{uri=~".*reserve.*",status=~"5.."}[5m]) > 0`. Declines are 4xx by design, so a 5xx is always a bug or an outage.
2. **`/readyz` failing** (database unreachable) for more than a minute.
3. **Connection pool saturation**: `hikaricp_connections_pending > 0` sustained, or p99 reserve latency above an agreed bound. This is how a stampede shows up first.
4. **Reconciliation drift**: `available + held + confirmed != total_seats` on `GET /shows/{id}`, or `reservations_confirmed_total` diverging from the confirmed-seat count. Either means the core promise is broken and sales should stop.
5. **Anomalous decline mix**, as a ticket rather than a page: a spike in `idempotency_conflict` suggests a client bug; a sudden drop in `seat_taken` during an on-sale suggests requests are not reaching the service.

During a normal on-sale, large numbers of `seat_taken` 409s are expected and healthy.

## 6. AI usage

**Tool:** Claude (Anthropic), used in a chat conversation. `<edit to reflect any other tools you used>`

**What I directed:**
* The stack: Java with Spring Boot for the backend and React for the UI, and that the solution must comply with the assignment text.
* Deployment questions: which platform to use, what is needed locally, how the admin token works, and how to test locally with Docker.
* Debugging: I ran the burst script locally on JDK 17, found that it failed to compile with my local `java` command, and had it rewritten to avoid newer language features. `<add other issues you found, and what you changed>`

**What the AI decided or generated (and I reviewed):**
* The overall design: Postgres with `SELECT ... FOR UPDATE` in sorted label order as the atomic decision; a per-user advisory lock for the per-user limit and idempotency; a unique `(user_id, idempotency_key)` constraint with a request hash; the explicit-cancel model; all-or-nothing multi-seat behaviour; returning 200 for replays.
* The code: all Java classes, `schema.sql`, the Dockerfile, compose and Render blueprint, the React UI, the burst script, and the first drafts of the README and this write-up.

**What I verified myself:** `<fill in honestly: e.g. built and ran with docker compose; ran ./burst.sh locally and against the live URL; the results; read through SeatService.doReserve and can explain each step; tested readiness by stopping the database; anything you changed>`

**Honest limits:** the AI-generated code was not compiled by the AI itself; I compiled and ran it. `<edit or remove>` There are no automated unit tests, and the burst script is the main test. I can explain and extend the locking, idempotency and cancel logic, for example by adding TTL holds or best-effort booking.

## 7. What I would do next

* Time-boxed holds with lazy expiry (section 3) and a confirm step, plus a payment step keyed by the idempotency key.
* Automated tests: Testcontainers with hundreds of threads on one seat, a multi-seat deadlock test, and an idempotency test.
* A real identity provider instead of the demo token issuer; rate limiting; per-user quotas across shows.
* Pagination (or a cursor) for `GET /shows/{id}` on very large halls, and partitioning of `seats` by show.
* Custom latency histograms for the reserve path, a Grafana dashboard, and alert rules from section 5.
* A read replica for show state and a CDN-friendly cached summary endpoint for the on-sale page.
* Idempotency-key expiry and cleanup for old records.