# Event Ticket & Payment Orchestration Platform — Day 1 Baseline

Interview-focused backend project: an event ticketing domain that reserves seats with
strong concurrency guarantees. Payment orchestration arrives in later milestones;
this baseline covers identity, catalog, event-specific seat inventory, and
transactional seat holds.

## Day 1 scope (implemented)

- Registration/login with Spring Security, BCrypt, and JWT (stateless, 15-minute tokens).
- Read-only event catalog (`GET /api/v1/events`, `GET /api/v1/events/{id}/seats`).
- Physical `seats` modeled separately from sellable `event_seats` inventory
  (`UNIQUE(event_id, seat_id)` — the same physical seat sells independently per event).
- Atomic hold acquisition via PostgreSQL `SELECT ... FOR UPDATE`; exactly one
  concurrent hold per event-seat can succeed (proven by test).
- Hold expiry: scheduled release plus lazy expiry inside the hold transaction.
  Only `HELD` rows with a past expiry are released — `SOLD` is never auto-released.
- Consistent error responses, correlation IDs (`X-Correlation-ID`), Flyway-managed
  schema, Swagger UI, Actuator health.

## Prerequisites

- JDK 21 or newer (project targets Java 21; build verified with JDK 24).
- Maven 3.9+ (or use your own install; no wrapper is bundled yet).
- Docker Desktop (for PostgreSQL).

## Start PostgreSQL

```bash
docker compose up -d
```

The service publishes the container's PostgreSQL port 5432 on **host port 5433**
(`"5433:5432"` in `docker-compose.yml`) because host port 5432 is commonly taken by
a locally installed PostgreSQL service. The application default JDBC URL already
points at `localhost:5433`; override with `DATABASE_URL` if your setup differs.

## Run the tests

```bash
mvn clean test
```

14 integration tests run against real PostgreSQL (Compose-backed): authentication,
catalog reads, single/concurrent hold acquisition (8 threads, exactly 1 winner),
same-physical-seat independence across events, hold expiry, and auth rejection.

## Configuration & credentials (local development only)

All default credentials in `docker-compose.yml` and `src/main/resources/application.yml`
are **local-development placeholders only** and must be overridden with strong,
secret-managed values in any deployed environment:

| Purpose            | Environment variable |
|--------------------|----------------------|
| JDBC URL           | `DATABASE_URL`       |
| Database user      | `DATABASE_USER`      |
| Database password  | `DATABASE_PASSWORD`  |
| JWT signing secret | `JWT_SECRET` (min. 32 characters, enforced at startup) |

Other knobs: `app.hold.ttl-minutes` (default 10), server port 8080,
Swagger UI at `/swagger-ui.html`.

## Database

Flyway applies versioned migrations from `src/main/resources/db/migration`
(`V1__init.sql` schema, `V2__seed.sql` demo venue/events/seats); Hibernate runs in
`validate` mode and never alters the schema.
