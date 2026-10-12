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
}
