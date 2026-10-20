-- V7: refunds ledger for Day 5 — one full refund per payment, explicit Admin action.
--
-- Contract with PaymentProvider.refund()/lookupRefund():
--   * provider_refund_ref is generated and persisted here BEFORE any provider I/O;
--     every re-drive reuses the same reference, which providers must deduplicate.
--   * lookupRefund(refund_ref) is independent of the charge lookup: present+success
--     = definitive success, present+declined = definitive decline, empty = provider
--     has NO record of this refund (nothing was refunded), ambiguous exception =
--     outcome unknown. An empty result therefore permits a safe same-reference
--     re-drive; ambiguity never does.
--
-- Limits (documented, deliberate): exactly one FULL refund per payment (no partials,
-- no retry after FAILED), amounts must equal the captured payment (enforced in the
-- service — PostgreSQL cannot express a cross-table amount CHECK). The mock provider
-- demonstrates application plumbing only; it cannot prove a real provider's
-- idempotency guarantees.
--
-- V1-V6 are never edited. Two constraints created inline by V5/V6 are replaced here
-- with additive forms (PostgreSQL inline CHECK constraints cannot be altered in place).

-- payments.status: add REFUNDED (V5 created this as an unnamed inline CHECK, which
-- PostgreSQL names payments_status_check).
ALTER TABLE payments DROP CONSTRAINT IF EXISTS payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check
  CHECK (status IN ('CREATED', 'AUTHORIZING', 'SUCCEEDED', 'FAILED', 'UNKNOWN', 'REFUNDED'));

-- reconciliation_records.provider_verdict: add REFUND_COMPLETED (V6 inline CHECK ->
-- reconciliation_records_provider_verdict_check).
ALTER TABLE reconciliation_records DROP CONSTRAINT IF EXISTS reconciliation_records_provider_verdict_check;
ALTER TABLE reconciliation_records ADD CONSTRAINT reconciliation_records_provider_verdict_check
  CHECK (provider_verdict IN (
    'PROVIDER_SUCCESS',
    'PROVIDER_SUCCESS_BOOKING_EXPIRED',
    'PROVIDER_DECLINED',
    'MANUAL_REVIEW',
    'REFUND_COMPLETED'
  ));

CREATE TABLE refunds (
  id UUID PRIMARY KEY,
  -- One refund per payment: UNIQUE makes concurrent duplicate submissions a
  -- database-level conflict, never a second provider operation.
  payment_id UUID NOT NULL UNIQUE REFERENCES payments (id) ON DELETE CASCADE,
  amount_minor BIGINT NOT NULL CHECK (amount_minor > 0),
  currency VARCHAR(3) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
    CHECK (status IN ('REQUESTED', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
  -- Stable provider reference, assigned locally before any external call.
  provider_refund_ref VARCHAR(64) NOT NULL UNIQUE,
  note VARCHAR(200),
  triggered_by_user_id UUID NOT NULL REFERENCES users (id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Reconciler selector: aged in-flight refunds (updated_at is refreshed on every
-- check that resolves nothing, mirroring payments.last_reconciled_at).
CREATE INDEX idx_refunds_reconcilable ON refunds (status, updated_at)
  WHERE status IN ('REQUESTED', 'UNKNOWN');
