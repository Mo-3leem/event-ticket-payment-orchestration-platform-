# Interview and System-Design Guide

This guide is a study aid for explaining the repository. It describes the
implemented system and its limits; it is not evidence of production readiness.

## 30-second summary

"I built a ticketing backend where seat inventory, holds, bookings, payments,
reconciliation, and refunds are coordinated through PostgreSQL. The interesting
part is failure handling: provider timeouts become an explicit `UNKNOWN` state
instead of false failures, every retry uses a stable provider reference, and a
reconciler resolves only definitive provider outcomes. The system is a modular
monolith with idempotent payment APIs, protected financial audit data, and
real-PostgreSQL concurrency tests."

## Two-minute walkthrough

1. **Catalog and inventory:** events reference physical seats, but each event
   gets its own `event_seats` row. That row is the unit of concurrency.
2. **Hold:** acquiring a seat locks the `event_seats` row with
   `SELECT ... FOR UPDATE`, verifies availability, and stores the holder and
   expiry. Expiry runs both lazily and on a scheduler.
3. **Booking:** checkout locks the user's held seats in sorted order, verifies
   ownership and expiry, creates a `PENDING_PAYMENT` booking, and inserts live
   booking items. A partial unique index prevents a second live booking for the
   same seat while preserving history after expiry.
4. **Payment:** `POST /api/v1/payments` requires an idempotency key. Tx1 stores
   the key and payment intent. The provider call happens outside the
   transaction. Tx2 re-locks the payment, records the attempt, transitions the
   payment, confirms the booking and marks seats `SOLD` on success, and stores
   the replay response.
5. **Ambiguity:** timeout or transport failure becomes `UNKNOWN`. A scheduled
   reconciler calls the provider by the original reference. It resolves only
   success or decline; an ambiguous lookup stays `UNKNOWN`.
6. **Refunds:** Admins can request one full refund for a captured payment. The
   refund reference is persisted before I/O. Recovery uses that same reference;
   an ambiguous lookup is never blindly re-driven.
7. **Audit:** Payment attempts and reconciliation records are append-only; each
   refund is a single row whose status transitions in place. `RESTRICT` foreign
   keys prevent a payment deletion from erasing financial history.

## Whiteboard order

Use this order to keep the design discussion coherent:

1. Draw clients, one Spring Boot service, PostgreSQL, and the provider adapter.
2. Draw the data model around `event_seats`, `bookings`, `booking_items`, and
   `payments`.
3. Explain the hold and booking concurrency path with row locks and indexes.
4. Explain the payment timeline: Tx1, provider call, Tx2.
5. Put `UNKNOWN` on the board and explain reconciliation.
6. Add refunds and audit history.
7. Finish with limits, trade-offs, and what you would change under more scale.

## Core invariants

| Invariant | Mechanism | Evidence |
|-----------|-----------|----------|
| At most one live hold per event seat | `SELECT ... FOR UPDATE` on `event_seats`; status and holder checks | `HoldTest`, `HoldConcurrencyTest` |
| At most one live booking per event seat | Partial unique index `(event_seat_id) WHERE released = false`; sorted seat locks | `V4` migration, booking tests |
| At most one payment per booking | `UNIQUE booking_id`; booking row lock before creation | `V5` migration, `PaymentsTest` |
| Retries do not create a second payment | User-scoped idempotency key plus fingerprint and stored response | `PaymentsTest`, `PaymentService` |
| Ambiguous provider results do not become failures | `TIMEOUT`/`ERROR` transition to `UNKNOWN` | `PaymentService.finishAttempt` |
| A resolution is recorded once | Tx2 re-locks and re-verifies `UNKNOWN` before writing | `ReconciliationTest` |
| At most one refund per payment | `UNIQUE payment_id`; payment row lock before refund intent | `V7` migration, `RefundTest` |
| Financial history cannot cascade-delete | `RESTRICT` foreign keys | `V8` migration |
| Seats of expired bookings can be sold again | `released` flag and partial unique index | `V4`, `BookingTest` |
| Startup cannot serve before admin bootstrap resolves | Highest-precedence gate filter returns `503` until the gate opens | `AdminBootstrapGateTest`, `AdminBootstrapGateWebTest` |

## Deep dive: seat contention

**Question:** Two users try to hold the same seat at the same time. What
happens?

**Answer:** Both transactions attempt `SELECT ... FOR UPDATE` on the same
`event_seats` row. One obtains the lock; the other waits. The winner verifies
availability, writes `HELD`, and commits. The second transaction then sees
`HELD` and returns a conflict. The database lock, not application timing, is the
arbiter. A unique booking-item index is a second line of defense if concurrent
booking creation bypasses the expected path.

**Follow-up:** Why sort seat IDs? Booking multiple seats locks them in
deterministic order to avoid deadlocks between concurrent multi-seat requests.

## Deep dive: lost payment response

**Question:** The provider charged the customer, but the response timed out.
How do you avoid charging twice or losing the sale?

**Answer:** The payment row and stable `provider_ref` were committed before the
call. The timeout records `UNKNOWN`, not `FAILED`, and stores an attempt row.
The recovery path calls `lookup(provider_ref)`:

- success: finalize `SUCCEEDED`, confirm the booking if still pending, or flag
  the payment for refund if the booking expired;
- decline: finalize `FAILED`;
- empty/ambiguous: leave `UNKNOWN` and refresh the reconciliation timestamp.

A stale idempotency key follows the same recovery path. If the lookup proves the
provider has no record, one same-reference re-drive is safe only because the
provider contract requires deduplication by that reference.

**Important limit:** The application does not claim exactly-once charging. The
real provider must prove idempotency and lookup consistency before production.

## Deep dive: duplicate refund

**Question:** Two admins click refund at the same time. Can the customer be
refunded twice?

**Answer:** Tx1 locks the payment, verifies it is `SUCCEEDED`, checks for an
existing refund, and inserts a refund row with a unique `payment_id`. The
second request either waits and then sees the existing refund, or loses the
unique insert and receives a conflict. The provider is called only after the
durable intent commits. Recovery uses the same `provider_refund_ref`; a
definitive decline is terminal and ambiguous outcomes stay `UNKNOWN`.

## Trade-offs and likely questions

### Why a monolith instead of microservices?

The core invariants cross inventory, bookings, and payments. A single database
transaction is simpler and safer at this scale. Extraction would add network
failures, compensation, and operational overhead before there is evidence of a
scaling or ownership boundary.

### Why not Kafka or an outbox?

The current flow needs a durable local intent, not asynchronous command
delivery. Tx1 plus a stable reference gives recovery a concrete record. An
outbox would help publish events or drive retries reliably, but it does not
remove the need for provider idempotency and lookup. It is a reasonable next
step for webhooks, notifications, or analytics.

### Why polling instead of webhooks?

Webhooks require endpoint security, replay protection, ordering decisions, and
provider-specific verification. Polling is simpler and works for the mock
provider. A production design would likely add signed webhooks with
deduplication, while retaining lookup as the reconciliation backstop.

### Why not Redis or an in-memory lock?

Correctness must survive multiple instances and restarts. PostgreSQL row locks
and constraints provide a single authority. A cache could improve read latency
but would introduce invalidation and split-brain problems if used for
correctness.

### Why is `UNKNOWN` not retried automatically?

Retrying a charge whose outcome is unknown can double-charge. Lookup first. Only
a provider response proving no record exists permits a same-reference re-drive.

### Why are refunds full-only and Admin-only?

Partial refunds, user cancellation, and automatic expiry refunds are unresolved
product policies. Implementing them without requirements would create money
movement rules that the business has not approved. The current endpoint makes
the narrow, explicit behavior auditable.

### Why JWT without refresh or revocation?

It keeps authentication stateless for this backend. The trade-off is that
logout and per-token revocation are not supported. Role changes take effect on
the next request because the role is reloaded from the database. Rotating the
signing secret invalidates all tokens. A production system with long-lived
sessions would need refresh rotation, revocation, or a session store.

### Why real PostgreSQL for tests?

The correctness claims depend on PostgreSQL locking, partial indexes,
`SKIP LOCKED`, constraints, and transaction behavior. H2 would pass different
semantics. Testcontainers was not usable in this environment, so the suite
requires an explicitly provisioned isolated database guarded before connection.

### What happens if the application crashes after the provider call?

The payment remains `AUTHORIZING` with a persisted `provider_ref`. A retry with
the same idempotency key after the reclaim threshold performs a provider lookup
and finalizes the known outcome. If there is no provider record, the same
reference is re-driven safely under the provider contract.

### What happens if payment succeeds after booking expiry?

The payment is truthfully recorded as `SUCCEEDED` with
`BOOKING_EXPIRED_AT_CONFIRM`. The booking stays expired and its seats stay
released. The payment appears in the Admin refund-required list; no automatic
refund is performed.

## Scaling and operations discussion

Current bottlenecks and limits:

- hot-seat contention serializes on one `event_seats` row;
- every authenticated request performs a user lookup for the current role;
- scheduled reconciliation scans bounded batches in the API process;
- multiple instances duplicate scheduler work, although row locks preserve
  correctness;
- there is no leader election, rate limiting, CORS policy, or webhook path;
- provider timeout and recovery latency are operational concerns, not purely
  application concerns.

Useful improvements to propose, clearly labeled as not implemented:

- metrics for `UNKNOWN` age, reconciliation failures, provider latency, lock
  timeouts, refund backlog, and bootstrap gate duration;
- tracing across correlation IDs and provider calls;
- a dedicated job worker with leasing or leader election;
- signed webhook ingestion with replay protection;
- automated local provisioning of disposable test databases (CI provisions a
  fresh PostgreSQL service per run);
- real-provider contract tests, sandbox evidence, and chaos tests for lost
  responses;
- retention and anonymization policy for old financial and user data;
- rate limiting, TLS termination, secret rotation, and backup/restore drills.

## Demo path

1. Start PostgreSQL with `docker compose up -d`.
2. Show the Flyway schema and the `event_seats` unique constraints.
3. Acquire two holds for the same seat and show one conflict.
4. Create a booking from the winning hold.
5. Initiate a payment with an idempotency key and show replay of the same
   response.
6. Explain the mock timeout scenario: `UNKNOWN`, then reconciliation by lookup.
7. Show the reconciliation audit endpoint.
8. Show the Admin refund endpoint and the one-refund uniqueness rule.
9. Close with the production gate: real-provider idempotency and lookup
   evidence, secrets, TLS, backups, and operational monitoring.

## Claims to avoid

Do not say the system is production-ready, processes real payments, guarantees
exactly-once charging, or has verified a real provider. The accurate claim is
that the repository demonstrates the orchestration and recovery design against
a mock provider, with real-PostgreSQL tests for the implemented behavior.
