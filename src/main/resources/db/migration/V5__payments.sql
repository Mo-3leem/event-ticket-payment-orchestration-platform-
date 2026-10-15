-- V5: payments and idempotency for Day 3 payment initiation (mock provider only).
--
-- One payment row per booking. Outcomes are recorded explicitly; an ambiguous
-- provider result stays UNKNOWN and is never auto-retried in Day 3.
-- Idempotency keys are user-scoped; retention cleanup (see scheduler) must only
-- remove keys whose payment reached a terminal SUCCEEDED/FAILED state, so an
-- expired key can never authorize a second charge for an unresolved payment.

CREATE TABLE payments (
  id UUID PRIMARY KEY,
  booking_id UUID NOT NULL UNIQUE REFERENCES bookings (id),
  user_id UUID NOT NULL REFERENCES users (id),
  amount_minor BIGINT NOT NULL CHECK (amount_minor >= 0),
  currency VARCHAR(3) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'CREATED'
    CHECK (status IN ('CREATED', 'AUTHORIZING', 'SUCCEEDED', 'FAILED', 'UNKNOWN')),
  provider_ref VARCHAR(64) NOT NULL UNIQUE,
  note VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payments_user ON payments (user_id);

-- Every provider interaction is recorded, including timeouts. Latency helps
-- distinguish real declines from ambiguous outcomes during later review.
CREATE TABLE payment_attempts (
  id UUID PRIMARY KEY,
  payment_id UUID NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
  outcome VARCHAR(20) NOT NULL
    CHECK (outcome IN ('SUCCESS', 'DECLINED', 'TIMEOUT', 'ERROR')),
  error_code VARCHAR(100),
  latency_ms BIGINT NOT NULL CHECK (latency_ms >= 0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_attempts_payment ON payment_attempts (payment_id);

CREATE TABLE idempotency_keys (
  id UUID PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users (id),
  idem_key VARCHAR(64) NOT NULL,
  payment_id UUID NULL REFERENCES payments (id) ON DELETE CASCADE,
  request_hash VARCHAR(64) NOT NULL,
  response_code INTEGER NULL,
  response_body TEXT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'IN_PROGRESS'
    CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
  expires_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_idempotency_user_key UNIQUE (user_id, idem_key)
);
CREATE INDEX idx_idempotency_expiry ON idempotency_keys (status, expires_at);
