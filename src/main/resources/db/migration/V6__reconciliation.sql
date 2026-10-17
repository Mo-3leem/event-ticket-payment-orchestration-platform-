-- V6: reconciliation audit trail for Day 4 ambiguous-payment recovery.
--
-- UNKNOWN payments are resolved by a scheduled reconciler consulting the provider.
-- Every resolution writes one reconciliation_records row (transitions only); checks
-- that change nothing only refresh payments.last_reconciled_at so they are not
-- queried continuously. Records are append-only at the application level: no code
-- path updates or deletes them, including manual review notes.

ALTER TABLE payments ADD COLUMN last_reconciled_at TIMESTAMPTZ NULL;

CREATE TABLE reconciliation_records (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
  before_status VARCHAR(20) NOT NULL,
  after_status VARCHAR(20) NOT NULL,
  provider_verdict VARCHAR(40) NOT NULL
    CHECK (provider_verdict IN (
      'PROVIDER_SUCCESS',
      'PROVIDER_SUCCESS_BOOKING_EXPIRED',
      'PROVIDER_DECLINED',
      'MANUAL_REVIEW'
    )),
  note VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_reconciliation_payment ON reconciliation_records (payment_id);

-- Serves the reconciler selector: aged UNKNOWN payments not recently checked.
CREATE INDEX idx_payments_unknown_recon ON payments (status, last_reconciled_at)
  WHERE status = 'UNKNOWN';
