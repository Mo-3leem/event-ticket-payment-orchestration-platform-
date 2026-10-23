# Payment Lifecycle

## Holds and bookings

1. A user acquires a hold on an `AVAILABLE` event seat (`POST /api/v1/holds`).
   The row becomes `HELD` with `held_by_user_id` and `hold_expires_at`.
2. Creating a booking consumes the caller's live holds and creates
   `PENDING_PAYMENT` booking items. The seat stays `HELD` but is now owned by the
   booking expiry path.
3. If the booking expires before a successful payment, the booking becomes
   `EXPIRED`, items are marked released, and seats return to `AVAILABLE`.
4. A successful payment confirms the booking atomically and marks its seats
   `SOLD`. Confirmation re-checks the hold deadline even if the expiry sweeper
   has not run yet: a booking past its deadline is expired and released instead
   of being confirmed. A payment captured after the booking expired does not
   confirm it; the payment is annotated `BOOKING_EXPIRED_AT_CONFIRM` and appears
   in the Admin refund-required list.

## Charge state machine

```
CREATED -> AUTHORIZING -> SUCCEEDED
                       -> FAILED        (definitive decline)
                       -> UNKNOWN       (timeout, transport error, ambiguity)

UNKNOWN -> SUCCEEDED    (reconciliation or stale-key recovery, provider confirms)
UNKNOWN -> FAILED       (reconciliation, provider declines)

SUCCEEDED -> REFUNDED   (Admin refund, provider confirms)
```

`UNKNOWN` is unresolved, not terminal: the retention job must keep its
idempotency key, and a stale-key retry performs lookup-based recovery instead of
returning the old response or starting a new charge. Nothing re-charges an
unresolved payment as a side effect. Recovery only consults the provider by the
original `provider_ref`; a lookup proving the provider has no record permits one
safe same-reference re-drive, protected by provider-side deduplication. Charge
and refund lookups are bounded by `app.*.provider-timeout-ms`; a lookup timeout
is ambiguous and is never treated as proof that the provider has no record.

## Idempotency

- `POST /api/v1/payments` requires `Idempotency-Key` (max 64 chars).
- Keys are scoped per user and bind to a request fingerprint (booking id).
- The fingerprint is checked before any replay, recovery, or reclaim path.
  Reusing a key with a different booking returns 422 even when the original
  payment is still in progress or `UNKNOWN`.
- A completed key replays the stored response verbatim while it is fresh. Once
  the key is stale, a completed-key retry over an unresolved payment performs
  lookup-based recovery; over a now-terminal payment it returns the current
  state with status 200 instead of a permanently stale `UNKNOWN` body.
- A fresh `IN_PROGRESS` key returns 409 (operation already running).
- A stale key over an unresolved payment triggers lookup-based recovery; if the
  key is past expiry it blocks instead of replaying a stale body or authorizing a
  new charge.
- Retention: a key is deleted only after its own `expires_at` and only when its
  payment is terminal (`SUCCEEDED`, `FAILED`, `REFUNDED`) or absent. Keys over
  `UNKNOWN` payments are retained indefinitely so an expired key can never
  authorize a second charge.

## Reconciliation

A scheduled pass first treats aged `AUTHORIZING` payments as crash-left work:
under a row lock it verifies the payment is still `AUTHORIZING` and transitions
it to `UNKNOWN`. The normal payment pass
then selects `UNKNOWN` payments whose `last_reconciled_at` (or creation time)
is at least `min-age-minutes` old, consults
`PaymentProvider.lookup(provider_ref)`, and applies only definitive outcomes:

| Lookup result | Action | Audit verdict |
|---------------|--------|---------------|
| success, booking `PENDING_PAYMENT` | succeed payment, confirm booking | `PROVIDER_SUCCESS` |
| success, booking no longer pending | succeed payment, annotate `BOOKING_EXPIRED_AT_CONFIRM` | `PROVIDER_SUCCESS_BOOKING_EXPIRED` |
| decline | fail payment | `PROVIDER_DECLINED` |
| empty or ambiguous | keep `UNKNOWN`, refresh `last_reconciled_at` | none |

Each resolution is written once. Repeated cycles never duplicate records. Manual
review appends a `MANUAL_REVIEW` record and changes no payment, booking, or seat.

## Refunds

- Only captured (`SUCCEEDED`) payments can be refunded.
- Exactly one **full** refund per payment; amounts are copied from the captured
  payment and re-verified before finalization. A payment with zero refundable
  amount is rejected instead of creating a meaningless provider refund.
- Refunds are initiated only by an Admin through the explicit endpoint; nothing
  creates refunds automatically.
- `provider_refund_ref` is generated and persisted before any provider I/O. Every
  re-drive uses the same reference.
- Recovery rules:
  - lookup present + success → `SUCCEEDED`, payment `REFUNDED`, one
    `REFUND_COMPLETED` record;
  - lookup present + decline → `FAILED` (terminal; no retry after `FAILED`);
  - lookup empty → provider has no record, so a same-reference re-drive is safe;
  - lookup ambiguous → stays `UNKNOWN`; never re-driven.
- Refunds never change booking status or seat status.

## Provider adapter contract

`PaymentProvider` requires a real implementation to guarantee:

1. `charge(reference, amount, currency)` is deduplicated by `reference` and is
   safe to call again with the same reference.
2. `lookup(reference)` returns the definitive charge outcome or throws
   `ProviderStatusUnknownException` when the outcome is ambiguous.
3. `refund(refundReference, chargeReference, amount, currency)` is deduplicated
   by `refundReference`.
4. `lookupRefund(refundReference)` returns the definitive refund outcome, empty
   only when the provider has no record of that reference, and throws when
   ambiguous.

The bundled mock provider demonstrates application plumbing only. It cannot prove
a real provider's idempotency or lookup consistency; enabling a real provider is
a production gate that requires contract tests against the provider's sandbox and
documented evidence for all four properties above.

## Open product decisions

These are deliberately not invented in code:

- Whether a user-initiated cancellation/refund flow should exist (booking status
  `CANCELLED` is reserved but unused).
- Whether partial refunds are needed.
- Whether captures after booking expiry should be refunded automatically or only
  by Admin action (current behavior: Admin action only).
- How long refund and audit history must be retained, and whether user deletion
  requires anonymization.
