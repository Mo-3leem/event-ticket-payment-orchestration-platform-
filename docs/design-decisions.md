# Design Decisions

This page consolidates the decisions that shape the implemented system. It is a
record of the current design, not a claim that every alternative has been
validated in production. Each decision lists the rationale and the conditions
that would justify revisiting it.

## D1. Modular monolith

**Decision:** Ship one Spring Boot application with package-level modules
(`identity`, `catalog`, `booking`, `payments`, `shared`).

**Why:** Seat inventory, bookings, payments, and refunds share transactional
invariants. One process and one database keep those transitions atomic without
distributed transactions or cross-service compensation.

**Consequences:** Deployment and scaling are coupled, and module boundaries are
enforced by convention rather than separate build artifacts. The design is
appropriate for the current domain size and operational scope.

**Revisit when:** Independent scaling, separate team ownership, or independent
release cadence justifies extraction. Split only around boundaries that no
longer require a shared transaction.

## D2. PostgreSQL is the source of truth for concurrency

**Decision:** Use PostgreSQL row locks, unique constraints, and conditional
queries as the concurrency authority. Do not add a cache or in-memory lock as
the correctness mechanism.

**Why:** A hold, booking, payment, or refund decision must be atomic with the
state it changes. Database constraints remain correct across multiple
application instances and process restarts.

**Consequences:** Contention appears as database lock waits, and hot seats or
payments can serialize. Correctness does not depend on scheduler timing or
single-instance deployment.

**Revisit when:** Measured contention or scale requires partitioning, queueing,
or a different inventory model. Any replacement must preserve the same
invariants under concurrency.

## D3. Physical seats are separate from sellable inventory

**Decision:** Model `seats` as a physical layout and `event_seats` as the
sellable row for one event. Enforce `UNIQUE (event_id, seat_id)`.

**Why:** The same physical seat can be sold independently for different events.
Availability, holder, and expiry belong to the event-specific row, not the hall
layout.

**Consequences:** Inventory queries join through `event_seats`, and each event
materializes its own inventory rows. The model supports independent event
sales without duplicating physical seat definitions.

## D4. A hold is event-seat state, not a separate table

**Decision:** Represent a hold as a `HELD` row in `event_seats` with
`held_by_user_id` and `hold_expires_at`. Expire holds lazily during hold
operations and periodically with `HoldExpiryJob`.

**Why:** One authoritative row avoids a second hold record that could disagree
with inventory. Locking that row serializes competing acquisitions and makes
exactly one winner per seat possible.

**Consequences:** There is no history of failed or released hold attempts.
Expired holds may remain stored until a scheduler or the next hold operation
cleans them, but they cannot be consumed. `SOLD` rows are never released by hold
expiry.

## D5. Preserve booking history while allowing rebooking

**Decision:** Keep expired booking rows and items. Mark items `released` and
enforce a partial unique index on live items:
`UNIQUE (event_seat_id) WHERE released = false`.

**Why:** A global unique constraint would block a seat forever after its first
expired booking. Deleting history would lose the audit trail. The partial index
keeps at most one live booking per seat while preserving expired records.

**Consequences:** Queries must distinguish live from released items. The
database remains the final defense against concurrent double-booking.

## D6. Durable intent, external call, then re-verified finalization

**Decision:** Payment and refund orchestration use three phases:

1. **Tx1:** persist the intent and stable provider reference under row locks.
2. **Provider call:** run outside every transaction and row lock, with a
   timeout on a dedicated executor.
3. **Tx2:** re-lock and re-verify state before recording the outcome, moving
   money state, confirming the booking, and appending audit history.

**Why:** Holding a database transaction open during external I/O would pin
connections and lock rows for the provider's latency. A distributed transaction
is unavailable. Committing the intent first ensures recovery has a stable
reference to look up.

**Consequences:** A provider call may be attempted more than once after a crash
or lost response. Safety depends on the provider deduplicating by the stable
reference. This is not an outbox and does not provide exactly-once delivery.
Recovery is lookup-based, not an automatic retry of an unresolved charge.

## D7. Stable provider references and provider-side deduplication

**Decision:** Generate and persist `provider_ref` for a payment and
`provider_refund_ref` for a refund before any external call. Every re-drive uses
the same value.

**Why:** A stable reference is the only safe way to correlate a retry, a
provider lookup, and a crash recovery with the original operation.

**Consequences:** Exactly-once charging is not claimed by this application.
The real provider must guarantee deduplication by reference and definitive
lookup semantics. The bundled mock demonstrates the contract but cannot prove a
real provider's behavior. Enabling a real provider is a production gate.

## D8. Idempotency keys are user-scoped and fingerprint-bound

**Decision:** Require `Idempotency-Key` for payment initiation. Scope keys by
user, bind them to a request fingerprint, store the response, and reject the
same key for a different booking with `422`.

**Why:** Clients retry after timeouts. Replaying the stored result prevents a
second payment without requiring the client to know whether the first request
completed.

**Consequences:** A fresh in-progress key returns `409`; a stale key over an
unresolved payment may trigger lookup-based recovery but never authorizes a new
charge. Keys are retained while their payment is unresolved. This protects
against duplicate charges at the cost of storage and recovery complexity.

## D9. Ambiguity is a first-class state

**Decision:** Map provider timeouts and transport errors to `UNKNOWN`, never to
`FAILED`. An indeterminate recovery lookup leaves the existing `UNKNOWN` state
unchanged. Resolve only definitive provider outcomes through scheduled
reconciliation.

**Why:** Treating an ambiguous result as a decline can release a seat after the
customer was charged. A separate state preserves the truth and makes recovery
explicit.

**Consequences:** Some payments remain `UNKNOWN` until the provider can answer.
There are no webhooks in the current implementation, so recovery latency is
bounded by the polling interval and provider behavior. Manual review notes are
audit-only and never move money.

## D10. One full refund per payment, explicit Admin action

**Decision:** Allow one full refund per captured payment, initiated only through
the Admin endpoint. Copy the captured amount and currency, use a stable refund
reference, and never retry a definitive refund decline.

**Why:** This scope avoids partial-refund allocation, multiple-refund
reconciliation, and user-initiated cancellation policy, all of which are open
product decisions.

**Consequences:** Customers cannot self-cancel or request a partial refund.
Captured payments for expired bookings are surfaced to Admins but are not
refunded automatically. Empty refund lookups permit one same-reference re-drive;
ambiguous lookups never do.

## D11. Protected financial history

**Decision:** Treat payment attempts, reconciliation records, and refunds as
financial history. Attempts and reconciliation records are append-only; a refund
is a single row per payment whose status transitions in place. Use `RESTRICT`
foreign keys so deleting a payment cannot cascade-delete any of them. Keep
idempotency keys separate as operational state.

**Why:** Audit and refund records must survive operational mistakes and future
cleanup scripts. Idempotency keys are deduplication state with their own
retention rule, not financial history.

**Consequences:** Deletion of a payment with financial history fails. There is
no application endpoint that deletes payments, bookings, refunds, or audit
records. Retention policy for financial history remains an open decision.
Idempotency keys are purged only after expiry and only for terminal payments or
orphan keys with no payment.

## D12. Stateless JWT authentication with per-request role lookup

**Decision:** Issue 15-minute HMAC-signed JWTs. Keep the server stateless for
sessions, but reload the user's current role from the database on each request.
Use BCrypt cost 12 and a one-time first-admin bootstrap.

**Why:** Stateless verification avoids a session store. Re-loading the role
makes DBA demotion or deletion effective on the next request instead of waiting
for token expiry.

**Consequences:** Every authenticated request performs a user lookup. There is
no refresh token, logout revocation, or per-token denylist. Rotating
`JWT_SECRET` invalidates all tokens. The first-admin bootstrap is a deployment
operation, not a public registration path.

## D13. In-process schedulers with database coordination

**Decision:** Run hold expiry, reconciliation, refund recovery, and idempotency
cleanup as Spring schedulers in the application process. Use row locks and
state re-verification rather than a separate worker system.

**Why:** This keeps deployment simple and avoids another infrastructure
dependency while preserving correctness under overlapping executions.

**Consequences:** Multiple instances may duplicate scheduled work. Row locks
prevent duplicate state transitions, but there is no leader election or
cross-instance fencing. Scheduler latency and availability are tied to the API
process.

**Revisit when:** Work volume, isolation requirements, or multi-instance
efficiency justifies a dedicated job system with leasing or leader election.

## D14. Test against real PostgreSQL, not an in-memory substitute

**Decision:** Run database integration tests against PostgreSQL and guard test
datasource configuration before a connection opens. Do not use H2 or
Testcontainers in the current environment.

**Why:** Row-lock behavior, partial indexes, `SKIP LOCKED`, constraints, and
transaction semantics are part of the correctness argument. H2 would not
validate them. The local Docker environment rejects Testcontainers' version
negotiation, so tests use an explicitly provisioned database instead.

**Consequences:** Tests require an isolated database and credentials. The guard
rejects missing or shared-database configuration, but it is defense-in-depth,
not automatic provisioning. Tests that commit fixtures clean up what they own;
test user accounts may accumulate.

## D15. Money is stored in minor units with an explicit currency

**Decision:** Store amounts as `BIGINT` minor units plus a three-letter currency
code. Copy both into refunds and verify they match before finalization.

**Why:** Integer minor units avoid floating-point rounding. An explicit currency
prevents mixing units across events or payments.

**Consequences:** Formatting and provider-unit conversion belong at the
boundary. Refund amounts cannot be edited independently of the captured
payment. Multi-currency conversion is not implemented.

## Open decisions

The following are intentionally not decided in code:

- Whether users can cancel bookings and trigger a refund flow.
- Whether partial refunds are required.
- Whether captures after booking expiry should be refunded automatically.
- How long refund and audit history must be retained, and whether user deletion
  requires anonymization.
- Which real payment provider to integrate and what evidence is required to
  accept its idempotency and lookup guarantees.

Do not treat any of these as implemented requirements without an explicit
decision.
