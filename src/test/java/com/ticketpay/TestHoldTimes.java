package com.ticketpay;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.jdbc.core.JdbcTemplate;

@TestComponent
public class TestHoldTimes {

  private final JdbcTemplate jdbc;

  public TestHoldTimes(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void expireInPast(UUID eventSeatId, Instant past) {
    // PG JDBC cannot infer a SQL type for java.time.Instant; Timestamp maps to TIMESTAMPTZ.
    jdbc.update(
        "UPDATE event_seats SET hold_expires_at = ? WHERE id = ?",
        Timestamp.from(past),
        eventSeatId);
  }

  public void expireBookingInPast(UUID bookingId, Instant past) {
    jdbc.update(
        "UPDATE bookings SET expires_at = ? WHERE id = ?", Timestamp.from(past), bookingId);
  }

  public void backdateKeyCreated(UUID keyId, Instant past) {
    jdbc.update(
        "UPDATE idempotency_keys SET created_at = ? WHERE id = ?", Timestamp.from(past), keyId);
  }

  public void backdateKeyExpiry(UUID keyId, Instant past) {
    jdbc.update(
        "UPDATE idempotency_keys SET expires_at = ? WHERE id = ?", Timestamp.from(past), keyId);
  }

  public void backdatePaymentCreated(UUID paymentId, Instant past) {
    jdbc.update(
        "UPDATE payments SET created_at = ? WHERE id = ?", Timestamp.from(past), paymentId);
  }

  public void backdateRefundUpdated(UUID refundId, Instant past) {
    jdbc.update(
        "UPDATE refunds SET updated_at = ? WHERE id = ?", Timestamp.from(past), refundId);
  }

  /**
   * Test-teardown only: fully removes a committed booking and every row that references
   * it (refunds, payments, attempts, keys, records, items), then returns its seats to
   * AVAILABLE. Keeps the shared 20-seat seed reusable across unlimited suite runs.
   * Never used by application code.
   */
  public void releaseCommittedBooking(UUID bookingId) {
    jdbc.update(
        "UPDATE event_seats SET status = 'AVAILABLE', held_by_user_id = NULL,"
            + " hold_expires_at = NULL WHERE id IN"
            + " (SELECT event_seat_id FROM booking_items WHERE booking_id = ?)",
        bookingId);
    jdbc.update(
        "DELETE FROM refunds WHERE payment_id IN"
            + " (SELECT id FROM payments WHERE booking_id = ?)",
        bookingId);
    jdbc.update(
        "DELETE FROM reconciliation_records WHERE payment_id IN"
            + " (SELECT id FROM payments WHERE booking_id = ?)",
        bookingId);
    jdbc.update(
        "DELETE FROM idempotency_keys WHERE payment_id IN"
            + " (SELECT id FROM payments WHERE booking_id = ?)",
        bookingId);
    jdbc.update(
        "DELETE FROM payment_attempts WHERE payment_id IN"
            + " (SELECT id FROM payments WHERE booking_id = ?)",
        bookingId);
    jdbc.update("DELETE FROM payments WHERE booking_id = ?", bookingId);
    jdbc.update("DELETE FROM booking_items WHERE booking_id = ?", bookingId);
    jdbc.update("DELETE FROM bookings WHERE id = ?", bookingId);
  }
}
