# Operations

## Local environment

```bash
docker compose up -d          # PostgreSQL 16 on host port 5433
mvn spring-boot:run           # application on :8080
```

Integration tests require an explicitly provisioned dedicated database:

```bash
export TEST_DATABASE_URL=jdbc:postgresql://localhost:5433/ticketpay_test
export TEST_DATABASE_USER=ticketpay_test
export TEST_DATABASE_PASSWORD='<dedicated test password>'
mvn clean test
```

The test guard rejects missing variables and the shared `ticketpay` database,
including URL-equivalent and percent-encoded forms, before opening a connection.
Compose creates empty `ticketpay_test` and `ticketpay_it` databases on first
volume initialization. The test suite does not create, reset, or provision them;
`docker compose down -v` is the destructive reset path. CI provisions a fresh
PostgreSQL 17 service per workflow run.
Database-free tests need no database URL:
`mvn '-Dtest=AdminBootstrapTest,AdminBootstrapGateTest,AdminBootstrapGateWebTest,TestDatabaseTest,TestDatasourceGuardInitializerTest' test`.

### Administrator bootstrap PostgreSQL test

`AdminBootstrapPostgresIT` is opt-in. The class name ends in `IT`, so ordinary
Surefire discovery does not include it in `mvn test`; selecting it explicitly
runs it. No Failsafe plugin is configured, so `mvn verify` does not run it
automatically. It requires a fresh disposable database with no `ADMIN` and writes
persistent test accounts. It verifies rollback after a failed insert, transaction
lock timeout, duplicate-email recovery, concurrent creation of exactly one
normalized `ADMIN` (a smoke test; lock timeout is the direct serialization
proof), restart idempotency, and refusal to promote an existing `USER`.

```bash
mvn -Dtest=AdminBootstrapPostgresIT test
```

Before running it, confirm the connection points at a dedicated, disposable
database that satisfies the fresh-database preconditions: no `ADMIN` and no data
from an earlier run of this suite. Set `TEST_DATABASE_URL`,
`TEST_DATABASE_USER`, and `TEST_DATABASE_PASSWORD` as above and verify the target
with `SELECT current_database()`. The suite writes persistent `ADMIN` and `USER`
rows and does not remove them; rerunning against a database already modified by
the suite may fail. The suite does not create, reset, or provision the database.
Compose creates an empty `ticketpay_it` on first volume initialization, and CI
creates a fresh one per workflow run; local reruns require a new empty database
or an explicit reset. Last verified on 2026-10-23 against a temporary, isolated
PostgreSQL 17.5 instance bound to `127.0.0.1:55440`: the full `mvn test` suite
passed 180/180 tests (15 Surefire classes; 10 database-backed and 5
database-free), and the opt-in `AdminBootstrapPostgresIT` class passed 6/6 on a
separate fresh database. These results cover the tested PostgreSQL
configuration, including advisory-lock timeout and rollback behavior; they do
not prove production concurrency outside that configuration.

Health: `GET /actuator/health` (status only). Swagger UI: `/swagger-ui.html`.

## Configuration

Runtime environment overrides: `DATABASE_URL`, `DATABASE_USER`,
`DATABASE_PASSWORD`, `JWT_SECRET` (minimum 32 characters),
`APP_BOOTSTRAP_ADMIN_EMAIL`, and `APP_BOOTSTRAP_ADMIN_PASSWORD`.

Test-only database overrides: `TEST_DATABASE_URL`, `TEST_DATABASE_USER`, and
`TEST_DATABASE_PASSWORD`. These are applied by the test datasource guard and are
required for every database-writing Spring Boot test context, including tests
that do not extend `BaseTest`; `DATABASE_URL` is not used as a test fallback.
The guard pins `spring.datasource.*`, `spring.datasource.hikari.*`, and
`spring.flyway.*` to the validated values, rejects `spring.datasource.jndi-name`,
a Hikari `data-source-class-name`, and Hibernate/JPA connection-URL properties,
and rejects the unsafe configuration for any datasource or Flyway bean before a
database connection is opened or a migration starts. `BaseTest` also validates
the variables while registering dynamic properties, so its subclasses fail
before the context creates a datasource. Database-free tests do not require
these variables.

While the built-in development JWT secret is in use, startup logs a warning; set
`JWT_SECRET` in every shared environment.

Scheduling knobs (`application.yml`):

| Property | Default | Meaning |
|----------|---------|---------|
| `app.hold.ttl-minutes` | 10 | hold/booking window |
| `app.payment.key-ttl-hours` | 24 | idempotency replay window |
| `app.payment.reclaim-after-seconds` | 120 | stale-key recovery threshold |
| `app.payment.cleanup-interval-ms` | 3600000 | key cleanup cadence |
| `app.reconciliation.min-age-minutes` | 10 | minimum age before recovery |
| `app.reconciliation.batch-size` | 50 | payments per pass |
| `app.reconciliation.interval-ms` | 300000 | scheduler cadence |
| `app.refund.min-age-minutes` | 10 | minimum refund age before recovery |
| `app.refund.batch-size` | 50 | refunds per pass |
| `app.payment.provider-timeout-ms` | 5000 | charge timeout |
| `app.refund.provider-timeout-ms` | 5000 | refund timeout |
| `spring.task.scheduling.pool.size` | 4 | scheduler threads; keeps hold expiry, reconciliation, and cleanup from starving each other |

Charge and refund provider lookups share their respective timeout budgets.
Timeouts are ambiguous outcomes: the payment/refund stays unresolved and is never
re-driven as if the provider had no record.

## First administrator provisioning

No administrator credential is seeded, committed, or exposed through an HTTP
endpoint. Provision the first `ADMIN` with a one-off application startup:

| Variable | Requirement |
|----------|-------------|
| `APP_BOOTSTRAP_ADMIN_EMAIL` | Unused by any existing account; validated as an email |
| `APP_BOOTSTRAP_ADMIN_PASSWORD` | At least 16 characters and at most 72 UTF-8 bytes |

1. Inject both values from a secret manager or a temporary process environment.
   Do not commit them or paste the password into shell history.
2. Start one application instance before routing traffic to it. Bootstrap runs
   during startup under a transaction-scoped PostgreSQL advisory lock with a
   transaction-local 5-second `lock_timeout`.
3. Confirm the startup log reports either that the initial `ADMIN` was created or
   that bootstrap was skipped. Logs must never contain the password or its hash.
4. Log in and verify the response role is `ADMIN`, then call
   `GET /api/v1/admin/payments?size=1` with the bearer token and confirm `200`.
5. Remove both bootstrap variables and restart.

Behavior:

| State | Result |
|-------|--------|
| Neither variable set | Bootstrap disabled; gate opens after the no-op |
| One variable set | Startup fails with a configuration error |
| Password too short or over 72 UTF-8 bytes | Startup fails |
| No `ADMIN`, email unused | Creates one `ADMIN` with a BCrypt hash |
| `ADMIN` already exists | No-op; no additional account or credential is changed |
| Configured email belongs to a `USER` | Startup fails; the account is never promoted. This takes precedence even if an `ADMIN` exists |
| Advisory-lock wait exceeds 5 seconds | Startup fails; the transaction rolls back without provisioning and the gate stays closed |
| Concurrent bootstrap executions | Transaction-scoped advisory lock is intended to serialize; verify against real PostgreSQL |

The runner is disabled under the `test` profile. Startup begins with an HTTP
request gate closed: all routes, including `/actuator/health`, return `503` until
the bootstrap transaction commits successfully. The `503` carries a valid
`X-Correlation-ID` and the standard `ApiError` JSON body. The gate is registered
at highest precedence for all servlet dispatcher types, including async and error
dispatches. A no-op bootstrap opens the gate after completion; bootstrap failure,
including a lock timeout, leaves it closed and aborts startup. The gate covers
the window before the runner and is separate from readiness; readiness is not a
substitute for it.

Email addresses are trimmed and lower-cased with `Locale.ROOT` in bootstrap,
registration, login, and the user entity. This prevents casing or the default
locale from producing a different spelling of the configured account.

The lock is intended to make concurrent replicas safe, but a single one-off
startup is still the recommended operation. It relies on the current single-`DataSource`
`JpaTransactionManager` binding the same JDBC connection used by `JdbcTemplate`
to the transaction. The timeout is set with `SET LOCAL`, so it is scoped to the
bootstrap transaction and cannot leak through a pooled connection. The opt-in
PostgreSQL integration suite has verified the timeout and rollback behavior under
the tested configuration. If the unique insert conflicts, the retry reacquires
the advisory lock in its fresh transaction before deciding the outcome; a focused
unit test asserts that retry behavior. The test datasource guard covers the
Spring Boot datasource and Flyway connection paths used by this repository but
does not provision an isolated database automatically. It accepts Hikari and
`AbstractDriverBasedDataSource` implementations after validating their connection
settings, and rejects unknown `DataSource` types, JNDI configuration, Hikari
`data-source-class-name`, Hibernate/JPA connection URLs,
`hibernate.connection.datasource`, and a Hibernate `connection.provider_class`.
Residual paths are a non-Boot test context that constructs its own `DataSource`
outside the guard, direct `DriverManager` connections, and database access that
bypasses Spring/Flyway. These safeguards are defense-in-depth, not a substitute
for a verified disposable test database and controlled environment variables.
Under the `test` profile, a test-only runner opens the gate so integration tests
can reach normal handlers.

### Administrator recovery and containment

- **All administrator access lost, no `ADMIN` remains:** rerun the one-off
  bootstrap with a new unused email. The bootstrap only runs when zero admins exist.
- **An `ADMIN` exists but its credentials are lost:** there is no supported
  self-service reset. Use a DBA-reviewed break-glass procedure against verified
  backups. This repository does not provide or approve SQL for that procedure; any
  manual path must be reviewed and verified by the operator.
- **Compromised administrator:** authorization reloads the role from the database
  on every request. A reviewed DBA demotion or deletion therefore takes effect on
  the next request. If tokens may be compromised, rotate `JWT_SECRET` and restart;
  this invalidates all existing tokens. There is no per-token revocation or admin
  lifecycle API.

## Migrations

Flyway applies `V1`…`V8` at startup. Applied migrations are immutable; corrections
ship as new migrations. `V8` protects financial history by changing payment
foreign keys from `CASCADE` to `RESTRICT`: deleting a payment that has attempts,
reconciliation records, or refunds now fails instead of silently deleting history.
`spring.flyway.baseline-on-migrate` is `false`. A non-empty schema without Flyway
history fails startup instead of being silently baselined; use a fresh database
or an explicitly reviewed baseline procedure for a legacy schema.

Before running the integration suite, set `TEST_DATABASE_URL`,
`TEST_DATABASE_USER`, and `TEST_DATABASE_PASSWORD` to a dedicated test database.
Do not use the shared default `ticketpay` database or production; the guard
rejects missing credentials and shared-database URL forms before connecting and
pins the datasource and Flyway aliases to the validated values. The
suite releases the seats and bookings it commits, but leaves test user accounts
behind; never point tests at a database whose data must be preserved.

## CI

[`.github/workflows/ci.yml`](../.github/workflows/ci.yml) runs on pushes to
`main`, pull requests, and manual dispatch. It uses Temurin JDK 25, starts a
PostgreSQL 17 service, runs `mvn -B -ntp clean test` against `ticketpay_test`,
creates a fresh `ticketpay_it`, runs `AdminBootstrapPostgresIT`, packages the
application, and uploads the jar. No Maven wrapper is bundled. CI depends on
network access to Maven Central and the PostgreSQL image; it does not deploy.

## Known dependency advisories

An OSV query-batch scan of the 122 resolved test-scope Maven artifacts on
2026-10-23 found only these two advisories. The verified Spring Boot 3.5.16 graph
retains `org.springframework:spring-webmvc:6.2.19` with:

- `GHSA-j9f9-w8pj-32f8` / `CVE-2026-47890` — SSE / view-fragment stream corruption.
- `GHSA-pc63-qcmh-9cmg` / `CVE-2026-47884` — `XsltView` SSRF / RCE.

No fixed Spring Framework 6.2.x release exists at the time of the last scan.
Code search found no SSE, `XsltView`, `ViewResolver`, or broad view-rendering
usage, but this is not proof that the advisories are unreachable. Treat the
application as not production-ready until a Spring Framework 7 / Spring Boot 4
migration is evaluated and verified, or a vendor-supported 6.2.x fix becomes
available. Re-run OSV or an equivalent scanner before each release.

## Observability

- Every response carries `X-Correlation-ID`; error bodies repeat it. Bootstrap
  gate `503` responses follow the same contract and are emitted before security
  or controllers run.
- Unexpected exceptions are logged server-side with the correlation ID and stack
  trace; clients receive a generic message.
- Reconciliation logs one warning per isolated item failure and one info line per
  non-empty resolved batch.
- Reconciliation selectors use `SKIP LOCKED`; safe with overlapping workers.

## Recovery playbooks

**Stuck `UNKNOWN` payment**

1. Inspect `GET /api/v1/admin/reconciliation?paymentId=…`.
2. Confirm `payments.last_reconciled_at` is advancing (the reconciler touched it).
3. If lookups are ambiguous, wait for the provider; never force a new charge.
4. If a booking was confirmed later, verify `booking_items` and `event_seats`.

**Crash-left `AUTHORIZING` payment**

1. The reconciliation job moves aged `AUTHORIZING` rows to `UNKNOWN` under a row
   lock before the normal lookup pass.
2. Verify `last_reconciled_at` advances on the following pass.
3. Do not manually re-drive a charge; resolve only by provider lookup.

**Refund stuck `REQUESTED`/`UNKNOWN`**

1. Inspect `GET /api/v1/admin/payments/{id}/refunds`.
2. The reconciler resolves by `provider_refund_ref`; empty lookup causes one safe
   same-reference re-drive, ambiguity never does.
3. Do not issue a second refund for the same payment; the ledger enforces one row.

**Capture after booking expiry**

1. Find candidates in `GET /api/v1/admin/payments/refund-required`.
2. Review the reconciliation record (`PROVIDER_SUCCESS_BOOKING_EXPIRED`).
3. Execute the refund explicitly; seats are already released and stay untouched.

**Duplicate-key / in-progress payment**

- 409 means another request owns the key. Poll `GET /api/v1/payments/{id}` or
  replay with the same key after the operation settles.

## Database hygiene

- Financial history is append-only at the application level. Deletion is blocked
  by `RESTRICT` foreign keys.
- Idempotency keys are purged only after their own expiry and only for terminal
  payments; orphan keys (no payment) are also eligible.
- Back up the database before any manual maintenance. No application endpoint
  deletes payments, bookings, refunds, or audit records.

## Security checklist for deployment

> **Not production-ready.** This checklist is not a production certification. The
> application still needs production JWT and database credentials, TLS, a reviewed
> migration/rollback strategy, backups and restore drills, monitoring, and
> real-provider contract validation before production use.

- Set `JWT_SECRET` to a private random value; rotate on exposure. Rotation
  invalidates every existing token.
- Use a dedicated database role; separate migration credentials from the runtime
  role where possible, since Flyway needs schema privileges.
- Run the first-admin bootstrap once, verify the account, then remove both
  bootstrap variables. Never promote a publicly self-registered account.
- Terminate TLS in front of the API; tokens are bearer credentials.
- Keep Actuator restricted to `health`/`info` (current configuration) and health
  details disabled.
- Do not expose Swagger UI publicly unless intended.
- Add rate limiting at the edge if the API is internet-facing.
- Validate a real payment provider against the adapter contract in
  [payment-lifecycle.md](payment-lifecycle.md) before enabling it.
