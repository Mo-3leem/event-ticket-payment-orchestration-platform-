package com.ticketpay.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payment_attempts")
public class PaymentAttempt {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_id", nullable = false)
  private Payment payment;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 20)
  private AttemptOutcome outcome;

  @Column(name = "error_code", length = 100)
  private String errorCode;

  @Column(name = "latency_ms", nullable = false)
  private long latencyMs;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected PaymentAttempt() {}

  public PaymentAttempt(Payment payment, AttemptOutcome outcome, String errorCode, long latencyMs) {
    this.id = UUID.randomUUID();
    this.payment = payment;
    this.outcome = outcome;
    this.errorCode = errorCode;
    this.latencyMs = Math.max(0, latencyMs);
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public AttemptOutcome getOutcome() {
    return outcome;
  }
}
