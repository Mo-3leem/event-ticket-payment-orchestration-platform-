# Operations

## Local environment

```bash
docker compose up -d          # PostgreSQL 16 on host port 5433
mvn spring-boot:run           # application on :8080
mvn clean test                # integration suite against the Compose database
```

Health: `GET /actuator/health` (status only). Swagger UI: `/swagger-ui.html`.

## Configuration

Environment overrides: `DATABASE_URL`, `DATABASE_USER`, `DATABASE_PASSWORD`,
`JWT_SECRET` (minimum 32 characters). While the built-in development JWT secret
is in use, startup logs a warning; set `JWT_SECRET` in every shared environment.

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

## Migrations

Flyway applies `V1`…`V8` at startup. Applied migrations are immutable; corrections
ship as new migrations. `V8` protects financial history by changing payment
foreign keys from `CASCADE` to `RESTRICT`: deleting a payment that has attempts,
reconciliation records, or refunds now fails instead of silently deleting history.

Before running the test suite, confirm the target database. The suite writes to
the Compose database (`localhost:5433/ticketpay`) and releases the seats and
bookings it commits, but leaves test user accounts behind; do not point tests at
a database whose data must be preserved.

## Observability

- Every response carries `X-Correlation-ID`; error bodies repeat it.
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

- Set `JWT_SECRET` to a private random value; rotate on exposure.
- Use a dedicated database role with only the privileges the app needs.
- Terminate TLS in front of the API; tokens are bearer credentials.
- Keep Actuator restricted to `health`/`info` (current configuration) and health
  details disabled.
- Do not expose Swagger UI publicly unless intended.
- Add rate limiting at the edge if the API is internet-facing.
- Validate a real payment provider against the adapter contract in
  [payment-lifecycle.md](payment-lifecycle.md) before enabling it.
