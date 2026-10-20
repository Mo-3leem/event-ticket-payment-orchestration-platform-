package com.ticketpay.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "payments",
    uniqueConstraints = {
      @UniqueConstraint(columnNames = "booking_id"),
      @UniqueConstraint(columnNames = "provider_ref")
    })
public class Payment {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "booking_id", nullable = false, updatable = false, unique = true)
  private UUID bookingId;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "amount_minor", nullable = false, updatable = false)
  private long amountMinor;

  @Column(name = "currency", nullable = false, length = 3, updatable = false)
  private String currency;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private PaymentStatus status;

  @Column(name = "provider_ref", nullable = false, updatable = false, length = 64, unique = true)
  private String providerRef;

  @Column(name = "note", length = 200)
  private String note;

  @Column(name = "last_reconciled_at")
  private Instant lastReconciledAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Payment() {}

  public Payment(UUID bookingId, UUID userId, long amountMinor, String currency) {
    this.id = UUID.randomUUID();
    this.bookingId = bookingId;
    this.userId = userId;
    this.amountMinor = amountMinor;
    this.currency = currency;
    this.status = PaymentStatus.CREATED;
    this.providerRef = this.id.toString();
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getBookingId() {
    return bookingId;
  }

  public UUID getUserId() {
    return userId;
  }

  public long getAmountMinor() {
    return amountMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public PaymentStatus getStatus() {
    return status;
  }

  public String getProviderRef() {
    return providerRef;
  }

  public String getNote() {
    return note;
  }

  public Instant getLastReconciledAt() {
    return lastReconciledAt;
  }

  /** Bookkeeping only: records that a reconciliation pass examined this payment. */
  public void touchReconciled(Instant now) {
    this.lastReconciledAt = now;
    this.updatedAt = now;
  }

  public void authorize() {
    if (status != PaymentStatus.CREATED) {
      throw new IllegalStateException("Only CREATED payments can start authorizing");
    }
    this.status = PaymentStatus.AUTHORIZING;
    this.updatedAt = Instant.now();
  }

  public void succeed() {
    if (status != PaymentStatus.AUTHORIZING
        && status != PaymentStatus.CREATED
        && status != PaymentStatus.UNKNOWN) {
      throw new IllegalStateException("Only AUTHORIZING or UNKNOWN payments can succeed");
    }
    this.status = PaymentStatus.SUCCEEDED;
    this.updatedAt = Instant.now();
  }

  public void fail(String reason) {
    if (status != PaymentStatus.AUTHORIZING
        && status != PaymentStatus.CREATED
        && status != PaymentStatus.UNKNOWN) {
      throw new IllegalStateException("Only AUTHORIZING or UNKNOWN payments can fail");
    }
    this.status = PaymentStatus.FAILED;
    this.note = reason;
    this.updatedAt = Instant.now();
  }

  public void markUnknown() {
    if (status != PaymentStatus.AUTHORIZING && status != PaymentStatus.CREATED) {
      throw new IllegalStateException("Only AUTHORIZING payments can become UNKNOWN");
    }
    this.status = PaymentStatus.UNKNOWN;
    this.updatedAt = Instant.now();
  }

  /**
   * Definitive full refund confirmed by the provider. Only a captured (SUCCEEDED)
   * payment may reach this — declines and ambiguous results never do.
   */
  public void markRefunded() {
    if (status != PaymentStatus.SUCCEEDED) {
      throw new IllegalStateException("Only SUCCEEDED payments can be marked REFUNDED");
    }
    this.status = PaymentStatus.REFUNDED;
    this.updatedAt = Instant.now();
  }

  public void annotate(String note) {
    this.note = note;
    this.updatedAt = Instant.now();
  }

  public boolean isTerminal() {
    return status == PaymentStatus.SUCCEEDED
        || status == PaymentStatus.FAILED
        || status == PaymentStatus.UNKNOWN
        || status == PaymentStatus.REFUNDED;
  }

  public boolean isUnresolved() {
    return status == PaymentStatus.CREATED || status == PaymentStatus.AUTHORIZING;
  }
}
