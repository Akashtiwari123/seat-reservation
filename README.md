# Seat Reservation at Scale

A JSON HTTP service that sells assigned seats for a show and stays correct when thousands of buyers hit the same seats at on-sale time: no seat sold twice, no user over their limit, no retried request double-charged.

**Stack:** Java 17, Spring Boot 3.4, PostgreSQL 16 (JDBC, no ORM), Micrometer/Prometheus, React (Vite) UI served by the same container.

| | |
|---|---|
| **Live URL** | `<https://YOUR-APP.onrender.com>` |
| **Health / readiness** | `<LIVE_URL>/healthz` , `<LIVE_URL>/readyz` |
| **Metrics (Prometheus)** | `<LIVE_URL>/metrics` |
| **Logs** | `<Render dashboard > seat-reservation > Logs>` (structured JSON). `<Public log link or screen-recording link, if any>` |
| **Admin token** | `<provided in the submission message>` (local default: `admin-token`) |
| **Design write-up** | [WRITEUP.md](WRITEUP.md) |

---

## Quick start (local)

Prerequisites: Docker Desktop (Compose v2) and **JDK 17** (only needed to run the burst script). No local Postgres is needed; compose starts one. Check with `java -version` (it must report 17).

```bash
docker compose up --build      # app on http://localhost:8080 (UI at /)
./burst.sh http://localhost:8080
docker compose down -v         # stop and wipe data
```

Run the burst without a local JDK (Docker only, uses a JDK 17 image):

```bash
docker run --rm -v "$PWD/scripts":/s eclipse-temurin:17 java /s/Burst.java http://host.docker.internal:8080
```

## One-command burst

```bash
./burst.sh <BASE_URL>                      # or: make burst URL=<BASE_URL>
ADMIN_TOKEN=<token> ./burst.sh https://YOUR-APP.onrender.com    # against the live service
```
Requires **JDK 17** (`java scripts/Burst.java`; it uses the single-file launcher, so no separate compile step). On macOS/Linux run `ulimit -n 4096` first. Use `java -Dthreads=200 scripts/Burst.java <URL>` to lower client concurrency.

It creates a fresh 3000-seat show, mints tokens, and fires about 18,000 requests in random order:

| Scenario | Count | Expected |
|---|---|---|
| Hot-seat storm: 5 seats x 500 distinct users | 2,500 | exactly one 201 per seat, the rest 409 `seat_taken` |
| Spread: random seats from S6-S2000 | 15,000 | one winner per seat, the rest 409 |
| Same key, same seat, fired twice in parallel | 500 pairs | never two 201s (second is a 200 replay) |
| Same key, different seats | 100 | 409 `idempotency_conflict` |
| One user, 10 parallel reserves, limit 4 | 10 | at most 4 x 201 |
| Spoofed `user_id` in body, non-owner cancel, cancel then re-book | 1 each | token user only; 403; 201 |

While the burst runs, a sampler polls `GET /shows/{id}` and asserts `available + held + confirmed == total_seats` every ~150 ms. At the end it prints the outcome distribution, hot-seat winners, the final reconciliation, and the relevant `/metrics` lines, then `RESULT: PASS` or `RESULT: FAIL` (non-zero exit code).

### Sample result
```
<paste the output of your last run against the LIVE URL here>
```

---

## Authentication

* `POST /auth/token` with `{"user_id":"alice"}` returns `{"user_id":"alice","token":"..."}`. This is a demo issuer: anyone can mint a token for any user id.
* Send `Authorization: Bearer <token>`. The token is `base64url(user_id).HMAC-SHA256(user_id)`, so **identity is derived only from the verified token**. A `user_id` field in a request body is ignored.
* Admin endpoint (`POST /shows`) requires `Authorization: Bearer $ADMIN_TOKEN` (401 with no token, 403 with a user token).

## API

Money is always integer paise. All bodies and errors are JSON; errors look like `{"error":"seat_taken","message":"..."}`.

| Endpoint | Auth | Behaviour |
|---|---|---|
| `POST /shows` | admin | Body `{name, seats[], price_paise, per_user_limit?}` (limit defaults to 4). Returns **201** with the show and every seat `available`. |
| `POST /shows/{id}/reserve` | user | Body `{seats[], idempotency_key}` or header `Idempotency-Key`. See below. |
| `POST /reservations/{id}/cancel` | user (owner) | Frees the seats. 200 on success and on repeat, 403 if not the owner, 404 if unknown. |
| `GET /shows/{id}?include_seats=true` | public | Per-seat status plus `counts`. `include_seats=false` returns counts only (cheaper for big halls). |
| `GET /healthz` | public | Liveness. Does not touch the database. |
| `GET /readyz` | public | Readiness. Pings the database (2 s budget); **503** if unreachable. |
| `GET /metrics` | public | Prometheus text format. |

### Reserve outcomes

| Status | `error` | When |
|---|---|---|
| 201 | | New reservation, `"status":"confirmed"` |
| 200 | | Idempotent replay: same key and same request returns the original reservation (header `Idempotent-Replay: true`) |
| 409 | `seat_taken` | At least one requested seat is not available |
| 409 | `per_user_limit` | The user would hold more than `per_user_limit` seats for the show |
| 409 | `idempotency_conflict` | Same key reused with a different show or seat set |
| 404 | `show_not_found`, `seat_not_found` | Unknown show, or a seat label not in the show |
| 400 | `seats_required`, `bad_seat_label`, `duplicate_seats`, `idempotency_key_required`, ... | Invalid input |
| 401 | `unauthorized` | Missing or invalid user token |

**Multi-seat requests are all-or-nothing.** If any seat is unavailable, nothing is booked and the response is 409. This is enforced under row locks, so it holds under concurrency (see WRITEUP.md).

### Example
```bash
TOKEN=$(curl -s -XPOST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}' | sed 's/.*"token":"\([^"]*\)".*/\1/')

curl -s -XPOST $URL/shows -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'

curl -i -XPOST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"seats":["A1"],"idempotency_key":"order-1"}'
# 201 {"reservation_id":"...","show_id":"...","user_id":"alice","seats":["A1"],"amount_paise":25000,"status":"confirmed"}
```

---

## Observability

**Metrics** at `/metrics`:

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | Reservations confirmed |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_conflict` |
| `reservations_cancelled_total` | counter | Reservations cancelled |
| `seats_available{show_id}` | gauge | Available seats per show, read from the database every second |
| `http_server_requests_seconds_*{status,uri}` | histogram | Latency and status codes (use it to alert on 5xx) |
| `hikaricp_connections_*`, `jvm_*` | gauges | Connection pool and JVM health |

**Reconciling:** after a burst, `reservations_confirmed_total` should equal the number of `201`s, and `seats_available{show_id}` should equal `available` from `GET /shows/{id}?include_seats=false`. The gauge refreshes once per second, so allow about a second of lag.

**Logs:** one JSON line per request to stdout, with `request_id`, `user_id` (when authenticated), `status`, `duration_ms`, method and path. Send an `X-Request-Id` header to correlate your own tracing; otherwise one is generated and returned in the response header. View them with `docker compose logs -f app` locally, or in the Render dashboard.

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `PORT` | 8080 | HTTP port |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | localhost, 5432, seats, seats, seats | Postgres connection |
| `DB_POOL` | 40 | Hikari max pool size |
| `ADMIN_TOKEN` | `admin-token` | Bearer token for `POST /shows`. **Set a strong value in production.** |
| `TOKEN_SECRET` | `dev-secret-change-me` | HMAC key for user tokens. **Set a strong value in production.** |
| `JAVA_OPTS` | `-XX:MaxRAMPercentage=75` | JVM flags |

## Deploying to Render

1. Push this repo to GitHub.
2. Render, **New > Blueprint**, select the repo. `render.yaml` creates the Postgres database and the Docker web service and wires the DB variables.
3. Enter `ADMIN_TOKEN` when prompted. `TOKEN_SECRET` is generated automatically.
4. Wait for the build, then check `/healthz`, `/readyz`, `/metrics`.
5. Run the burst against the live URL and paste the output above.

The Dockerfile is multi-stage (Node builds the UI, Maven builds the jar with the UI embedded, a JRE image runs it), so a clean checkout builds the same way it deploys. Render's health check uses `/healthz`. Free instances sleep after inactivity, so the first request may take a while.

## Project layout

```
src/main/java/com/example/seats/
  SeatApplication.java   entry point, enables scheduling
  RequestFilter.java     request id, token -> identity, access log (JSON via MDC)
  Auth.java              HMAC-signed user tokens, admin token check
  ApiController.java     HTTP endpoints, status codes, auth checks
  SeatService.java       transactions, row locks, idempotency, limits, cancel, metrics
  SeatMetrics.java       seats_available gauge
  ApiHandler.java        exception -> JSON error mapping (declines are 4xx, never 5xx)
  ApiException.java      domain outcome with an HTTP status
src/main/resources/      application.yml, schema.sql
scripts/Burst.java       the stampede
frontend/                React UI (not graded)
Dockerfile, docker-compose.yml, render.yaml, Makefile, burst.sh
```

## Known limitations

* Holds use the **explicit-cancel model**; there is no automatic expiry, so `held` is always 0 (the invariant still sums correctly). See WRITEUP.md for how TTL holds would be added.
* `/auth/token` is a demo issuer with no credentials. Replace it with a real identity provider for production.
* Declined requests are not stored, so retrying a declined request with the same key re-evaluates it.
* No automated tests in the repo yet; `scripts/Burst.java` is the end-to-end check.