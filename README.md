# Event Ticket & Payment Orchestration Platform

Backend for event ticketing: seat inventory with strong concurrency guarantees,
transactional holds, bookings, and payment orchestration with idempotency,
ambiguous-outcome recovery, and Admin-driven refunds. Payments currently run
against a **mock provider**; the adapter boundary and the production gate for a
real provider are documented in [docs/payment-lifecycle.md](docs/payment-lifecycle.md).

## Implemented capabilities

- JWT authentication (stateless, BCrypt password hashing, 15-minute tokens) and
  role-based access (`USER`, `ADMIN`).
- One-time first-admin bootstrap from deployment secrets; public registration
  cannot create or promote an `ADMIN`.
- Public event catalog and event-specific seat inventory. Physical `seats` are
  modeled separately from sellable `event_seats` rows.
- Seat holds with PostgreSQL row locks: exactly one winner per event seat under
  concurrency. Expired holds are released by a scheduler and lazily inside hold
  operations; `SOLD` rows are never auto-released.
- Bookings consume live holds, expire with the hold window, and release seats on
  expiry. A seat can be rebooked after its booking expires (history preserved).
- Payment initiation with `Idempotency-Key`: one payment per booking, stored
  replay responses, fingerprint mismatch rejection (422), and stale-key recovery.
- Explicit outcomes only: declines become `FAILED`, ambiguous provider results
  become `UNKNOWN` and are never auto-retried as failures.
- Reconciliation of aged `UNKNOWN` payments via provider lookup, with an
  append-only audit trail (`reconciliation_records`). Manual review notes never
  move money.
- Admin refunds: exactly one **full** refund per captured payment, executed only
  through `POST /api/v1/admin/payments/{id}/refund`. Refund recovery re-drives the
  same stable provider reference only when a lookup proves the provider has no
  record; ambiguous outcomes are never blindly re-driven.
- Retention cleanup deletes idempotency keys only after their own expiry and only
  when the linked payment is terminal; refund and audit history are never deleted.
- Correlation IDs (`X-Correlation-ID`), consistent JSON errors, OpenAPI/Swagger UI,
  Actuator health, Flyway-managed schema.

## Architecture

```
com.ticketpay
├── identity    registration/login, JWT, roles
├── catalog     events, seats, event-seat inventory
├── booking     holds, bookings, expiry scheduling
├── payments    payments, idempotency, reconciliation, refunds
└── shared      security, correlation IDs, error handling, OpenAPI
```

Deep dives: [architecture](docs/architecture.md) ·
[payment lifecycle](docs/payment-lifecycle.md) · [operations](docs/operations.md).

## Prerequisites

- JDK 21 or newer (build verified with JDK 24).
- Maven 3.9+ (no wrapper is bundled).
- Docker Desktop for PostgreSQL 16.

## Start PostgreSQL

```bash
docker compose up -d
```

The container listens on **host port 5433** (host 5432 is commonly occupied).
The application defaults to `jdbc:postgresql://localhost:5433/ticketpay`.

## Run the tests

Database integration tests require an explicitly provisioned, dedicated test
database. A test-only Spring initializer applies the validated values to every
Spring Boot test context and guards any datasource or Flyway bean created in the
context, rejecting missing, incomplete, or shared-database configuration before
a database connection is opened. `BaseTest` also validates the required variables
while registering dynamic properties, so its subclasses fail before the context
creates a datasource. The initializer pins the validated values to
`spring.datasource.*`, `spring.datasource.hikari.*`, and `spring.flyway.*`, so
environment, system-property, or `application.yml` aliases cannot redirect the
datasource or Flyway. JNDI datasources, a Hikari `data-source-class-name`, and
Hibernate/JPA connection-URL properties are rejected. Tests that do not extend
`BaseTest` are guarded too whenever they create a datasource or a Flyway bean;
database-free tests do not require the variables.

```bash
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/ticketpay_test
export TEST_DATABASE_USER=ticketpay_test
export TEST_DATABASE_PASSWORD='<dedicated test password>'
mvn clean test
```

The suite runs against real PostgreSQL (Compose-backed, same engine and locking
semantics as production). It covers authentication, catalog, hold concurrency,
booking expiry, payment outcomes and idempotency races, reconciliation races,
refund races, batch-failure isolation, retention boundaries, and HTTP error
mapping. Tests release the seats and bookings they commit, but test user accounts
accumulate over time. Never point the suite at the shared `ticketpay` database or
production. The guard covers the Spring Boot datasource and Flyway paths used by
this repository. It accepts Hikari and `AbstractDriverBasedDataSource`
implementations after validating their connection settings. For Spring
datasource beans, it rejects other `DataSource` types. A custom Flyway
`DataSource` is accepted only when Flyway's configured URL, username, and
password match the validated test target; otherwise it is rejected. It also
rejects JNDI configuration, Hikari `data-source-class-name`, Hibernate/JPA
connection URLs, `hibernate.connection.datasource`, and a Hibernate
`connection.provider_class`. Residual paths are a non-Boot test context that
constructs its own `DataSource` outside the guard, direct `DriverManager`
connections, and database access that bypasses Spring/Flyway. These safeguards
are defense-in-depth, not a substitute for a verified disposable test database
and controlled environment variables.

Database-free tests do not need a database URL:

```bash
mvn '-Dtest=AdminBootstrapTest,AdminBootstrapGateTest,AdminBootstrapGateWebTest,TestDatabaseTest,TestDatasourceGuardInitializerTest' test
```

The web-context gate test starts a real embedded servlet container without a
datasource and exercises the registered filter chain, Spring Security, the two
public endpoints, and `/actuator/health`.

`AdminBootstrapPostgresIT` has been executed against PostgreSQL 17.5 and passed
6/6 on a fresh, isolated database, including the advisory-lock timeout and
rollback paths. Under the current single-`DataSource` `JpaTransactionManager`
configuration, Spring binds the same JDBC connection used by `JdbcTemplate` to
the JPA transaction, so the transaction-scoped lock is held through
commit/rollback. The bootstrap sets a transaction-local `lock_timeout` of 5
seconds before acquiring the advisory lock; a timeout aborts the transaction and
leaves the gate closed. If the unique insert conflicts, the retry reacquires the
advisory lock in its fresh transaction before deciding the outcome; a focused
unit test asserts that retry behavior. The integration result covers the tested
configuration, not arbitrary production concurrency.

Flyway uses the guarded primary `DataSource` when present; the test initializer
also pins `spring.flyway.*` for the path where Flyway would build its own
migration datasource. Under the `test` profile, the production bootstrap runner
is disabled and a test-only runner opens the gate so integration tests can reach
normal handlers.

`AdminBootstrapPostgresIT` is the opt-in PostgreSQL test for bootstrap rollback,
advisory-lock timeout, duplicate-email recovery, concurrent creation (a smoke
test; the lock-timeout scenario is the direct serialization proof), restart
idempotency, and refusal to promote an existing `USER`. The class name ends in
`IT`, so ordinary Surefire discovery does not include it in `mvn test`; selecting
it explicitly runs it. This repository does not configure Failsafe, so
`mvn verify` does not run it automatically:

```bash
mvn -Dtest=AdminBootstrapPostgresIT test
```

It requires the same `TEST_DATABASE_*` variables and writes persistent rows. Use
a dedicated, disposable database that satisfies the suite's fresh-database
preconditions: no `ADMIN` and no data from an earlier run of this suite. The
suite creates `ADMIN` and `USER` rows and does not remove them; rerunning against
a database already modified by the suite may fail. No automatic cleanup, reset,
or database provisioning is performed. The repository does not create, reset, or
provision that database. Verify the target with `SELECT current_database()` before
running; never point it at the shared `ticketpay` database or production.

## Run the application

```bash
mvn spring-boot:run
```

- Health: `GET http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Provision the first administrator

There is no public admin registration endpoint and no seeded admin credential.
Provision the first `ADMIN` by starting the application once with both variables
injected from a secret manager or a one-off process environment:

| Variable | Requirement |
|----------|-------------|
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Unused by any existing account; validated as an email |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | At least 16 characters and at most 72 UTF-8 bytes |

Do not commit the password or put it in shell history. On startup the bootstrap
runs in a transaction with a 5-second, transaction-local lock timeout and a
PostgreSQL advisory lock:

- No `ADMIN` exists and the email is unused: creates an `ADMIN` with a BCrypt hash.
- An `ADMIN` already exists: no-op; no additional account or credential is changed.
- The email already belongs to a `USER`: startup fails; the account is never promoted.
  This check takes precedence even if another `ADMIN` exists.
- Only one variable is set: startup fails with a clear configuration error.
- Neither variable is set: bootstrap is disabled and the gate opens after the no-op.

The runner is disabled under the `test` profile. Startup begins with an HTTP
request gate closed: every route, including `/actuator/health`, returns `503`
until the bootstrap transaction has committed successfully. The `503` includes a
valid `X-Correlation-ID` and the standard `ApiError` JSON body. The gate is
registered at the highest precedence for every servlet dispatcher type, including
async and error dispatches. A no-op bootstrap opens the gate after completion; a
bootstrap failure (including a lock timeout) leaves it closed and aborts startup.
This protects the window before the runner, not just readiness probes. The
advisory lock is intended to serialize concurrent replicas, but the recommended
operation is still a one-off startup with the bootstrap variables removed
afterward.

Email addresses are trimmed and lower-cased with `Locale.ROOT` in bootstrap,
registration, login, and the user entity, so casing and the default locale cannot
create a second spelling of the same account.

Verify without exposing credentials:

1. Log in through `POST /api/v1/auth/login`; the response must contain
   `"role":"ADMIN"`.
2. Call `GET /api/v1/admin/payments?size=1` with the returned bearer token; it must
   return `200`.

Remove both bootstrap variables and restart after successful provisioning. If all
administrator access is lost, rerun the bootstrap only when no `ADMIN` remains. If
an `ADMIN` exists but is inaccessible, there is no supported self-service reset;
use a reviewed DBA break-glass procedure against verified backups. This repository
does not provide or verify SQL for that procedure.

For a compromised administrator, the role is reloaded from the database on every
request, so a reviewed DBA demotion or deletion takes effect on the next request.
Rotate `JWT_SECRET` to invalidate all existing tokens. No admin lifecycle or
per-token revocation API exists.

## Configuration

All defaults are local-development placeholders. Override them with environment
variables in any shared environment:

| Purpose            | Environment variable | Notes |
|--------------------|----------------------|-------|
| JDBC URL           | `DATABASE_URL`       | default `jdbc:postgresql://localhost:5433/ticketpay` |
| Database user      | `DATABASE_USER`      | default `ticketpay` |
| Database password  | `DATABASE_PASSWORD`  | default `ticketpay` (dev only) |
| JWT signing secret | `JWT_SECRET`         | minimum 32 characters; a warning is logged while the built-in dev secret is in use |
| First admin email  | `APP_BOOTSTRAP_ADMIN_EMAIL` | one-time; remove after successful provisioning |
| First admin password | `APP_BOOTSTRAP_ADMIN_PASSWORD` | one-time; 16+ characters, at most 72 UTF-8 bytes; remove after success |

Other knobs live under `app.*` in `src/main/resources/application.yml` (hold TTL,
provider timeouts, idempotency TTL, reconciliation batch size and interval, refund
batch size). Actuator exposes only `health` and `info`, and health details are
disabled.

> **Not production-ready.** Before any production use, replace the development JWT
> secret and database credentials, terminate TLS in front of the API, define and
> rehearse the migration/rollback and backup/restore strategy, and complete the
> real-provider contract validation described in
> [docs/payment-lifecycle.md](docs/payment-lifecycle.md).

## API overview

| Method | Path | Access | Purpose |
|--------|------|--------|---------|
| POST | `/api/v1/auth/register` | public | create account, returns JWT |
| POST | `/api/v1/auth/login` | public | authenticate, returns JWT |
| GET | `/api/v1/auth/me` | authenticated | current user id |
| GET | `/api/v1/events` | public | list events (stable ordering) |
| GET | `/api/v1/events/{id}` | public | event details |
| GET | `/api/v1/events/{id}/seats` | public | seat inventory (no holder identity) |
| POST | `/api/v1/holds` | user | acquire a seat hold |
| DELETE | `/api/v1/holds/{eventSeatId}` | user | release own hold |
| GET | `/api/v1/holds/mine` | user | actionable holds |
| POST | `/api/v1/bookings` | user | consume holds into a booking |
| GET | `/api/v1/bookings/me` | user | own bookings |
| POST | `/api/v1/payments` | user | initiate payment (`Idempotency-Key` required) |
| GET | `/api/v1/payments` | user | own payment history |
| GET | `/api/v1/payments/{id}` | owner | payment details |
| GET | `/api/v1/admin/payments` | admin | all payments (optional status filter) |
| GET | `/api/v1/admin/payments/refund-required` | admin | captured payments with expired bookings and no refund |
| POST | `/api/v1/admin/payments/{id}/refund` | admin | execute one full refund |
| GET | `/api/v1/admin/payments/{id}/refunds` | admin | refund history |
| GET | `/api/v1/admin/reconciliation` | admin | audit records |
| POST | `/api/v1/admin/reconciliation/{paymentId}/review` | admin | append a review note |

## Database

Flyway applies versioned migrations from `src/main/resources/db/migration`
(V1–V8). Hibernate runs in `validate` mode and never alters the schema.
Applied migrations are immutable; schema corrections are added as new migrations
(for example, `V8__protect_financial_history.sql` prevents cascade deletion of
payment attempts, reconciliation records, and refunds).

## Deliberate limitations

- The mock provider demonstrates plumbing only. Exactly-once charging depends on
  provider-side deduplication by a stable reference and must be validated against
  a real provider before production use.
- No webhooks or provider callbacks; ambiguous outcomes are recovered by polling
  lookups on a schedule.
- One full refund per payment, no partial refunds, no retry after a definitive
  refund decline.
- Payments captured after their booking expired are flagged for Admin refund;
  there is no automatic refund policy.
- Multi-instance deployments duplicate reconciliation work (safety is preserved by
  row locks); cross-instance leader fencing is out of scope.
- No CORS configuration; this is a same-origin/backend API.
