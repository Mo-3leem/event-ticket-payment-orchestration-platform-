# Event Ticket & Payment Orchestration Platform

Backend for event ticketing: seat inventory with strong concurrency guarantees,
transactional holds, bookings, and payment orchestration with idempotency,
ambiguous-outcome recovery, and Admin-driven refunds. Payments currently run
against a **mock provider**; the adapter boundary and the production gate for a
real provider are documented in [docs/payment-lifecycle.md](docs/payment-lifecycle.md).

## Implemented capabilities

- JWT authentication (stateless, BCrypt password hashing, 15-minute tokens) and
  role-based access (`USER`, `ADMIN`).
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

```bash
mvn clean test
```

The suite runs against real PostgreSQL (Compose-backed, same engine and locking
semantics as production). It covers authentication, catalog, hold concurrency,
booking expiry, payment outcomes and idempotency races, reconciliation races,
refund races, batch-failure isolation, retention boundaries, and HTTP error
mapping. The test suite and the application share the local Compose database;
tests release the seats and bookings they commit, but test user accounts
accumulate over time.

## Run the application

```bash
mvn spring-boot:run
```

- Health: `GET http://localhost:8080/actuator/health`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Configuration

All defaults are local-development placeholders. Override them with environment
variables in any shared environment:

| Purpose            | Environment variable | Notes |
|--------------------|----------------------|-------|
| JDBC URL           | `DATABASE_URL`       | default `jdbc:postgresql://localhost:5433/ticketpay` |
| Database user      | `DATABASE_USER`      | default `ticketpay` |
| Database password  | `DATABASE_PASSWORD`  | default `ticketpay` (dev only) |
| JWT signing secret | `JWT_SECRET`         | minimum 32 characters; a warning is logged while the built-in dev secret is in use |

Other knobs live under `app.*` in `src/main/resources/application.yml` (hold TTL,
provider timeouts, idempotency TTL, reconciliation batch size and interval, refund
batch size). Actuator exposes only `health` and `info`, and health details are
disabled.

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
