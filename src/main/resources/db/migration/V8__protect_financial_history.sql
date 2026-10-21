-- V8: financial history must never be cascade-deleted with its payment.
--
-- V5/V6/V7 created payment_attempts, reconciliation_records, and refunds with
-- ON DELETE CASCADE. Nothing in the application deletes payments, but a future
-- cleanup/reporting script could silently erase audit history and refund records
-- by deleting one payment row. RESTRICT makes that fail loudly instead.
--
-- Test teardown (TestHoldTimes.releaseCommittedBooking) already deletes refunds,
-- records, keys, and attempts before the payment, so it remains valid.
-- idempotency_keys keeps CASCADE: a key is operational deduplication state, not
-- financial history, and its own retention cleanup removes it independently.

ALTER TABLE refunds DROP CONSTRAINT IF EXISTS refunds_payment_id_fkey;
ALTER TABLE refunds ADD CONSTRAINT fk_refunds_payment
  FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT;

ALTER TABLE reconciliation_records DROP CONSTRAINT IF EXISTS reconciliation_records_payment_id_fkey;
ALTER TABLE reconciliation_records ADD CONSTRAINT fk_reconciliation_payment
  FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT;

ALTER TABLE payment_attempts DROP CONSTRAINT IF EXISTS payment_attempts_payment_id_fkey;
ALTER TABLE payment_attempts ADD CONSTRAINT fk_attempts_payment
  FOREIGN KEY (payment_id) REFERENCES payments (id) ON DELETE RESTRICT;
