package com.ticketpay.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-once refund ledger: exactly one row per payment (UNIQUE payment_id), one
 * full refund only. The provider refund reference is generated locally and persisted
 * with the intent, before any external call, and is reused for every re-drive.
 */
@Entity
@Table(
    name = "refunds",
    uniqueConstraints = @UniqueConstraint(columnNames = "payment_id"))
public class Refund {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @OneToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_id", nullable = false, unique = true)
  private Payment payment;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @Column(name = "currency", nullable = false, updatable = false, length = 3)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private RefundStatus status;

  @Column(name = "provider_refund_ref", nullable = false, updatable = false, length = 64)
  private String providerRefundRef;

  @Column(name = "note", length = 200)
  private String note;

  @Column(name = "triggered_by_user_id", nullable = false, updatable = false)
  private UUID triggeredByUserId;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Refund() {}

  public Refund(Payment payment, UUID triggeredByUserId, String note) {
    this.id = UUID.randomUUID();
    this.payment = payment;
    // Full refund only: the ledger copies the captured amount and currency, so a
    // refund can never differ from what was charged.
    this.amountMinor = payment.getAmountMinor();
    this.currency = payment.getCurrency();
    this.status = RefundStatus.REQUESTED;
    this.providerRefundRef = this.id.toString();
    this.note = note;
    this.triggeredByUserId = triggeredByUserId;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public Payment getPayment() {
    return payment;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public RefundStatus getStatus() {
    return status;
  }

  public String getProviderRefundRef() {
    return providerRefundRef;
  }

  public String getNote() {
    return note;
  }

  public UUID getTriggeredByUserId() {
    return triggeredByUserId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** A check that resolved nothing still refreshes the pacing timestamp. */
  public void touch(Instant now) {
    this.updatedAt = now;
  }

  public void succeed(Instant now) {
    if (status != RefundStatus.REQUESTED && status != RefundStatus.UNKNOWN) {
      throw new IllegalStateException("Only REQUESTED or UNKNOWN refunds can succeed");
    }
    this.status = RefundStatus.SUCCEEDED;
    this.updatedAt = now;
  }

  public void fail(Instant now) {
    if (status != RefundStatus.REQUESTED && status != RefundStatus.UNKNOWN) {
      throw new IllegalStateException("Only REQUESTED or UNKNOWN refunds can fail");
    }
    this.status = RefundStatus.FAILED;
    this.updatedAt = now;
  }

  public void markUnknown(Instant now) {
    if (status != RefundStatus.REQUESTED && status != RefundStatus.UNKNOWN) {
      throw new IllegalStateException("Only REQUESTED or UNKNOWN refunds can stay UNKNOWN");
    }
    this.status = RefundStatus.UNKNOWN;
    this.updatedAt = now;
  }

  public boolean isInFlight() {
    return status == RefundStatus.REQUESTED || status == RefundStatus.UNKNOWN;
  }
}
