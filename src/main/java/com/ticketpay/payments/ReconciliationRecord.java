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
@Table(name = "reconciliation_records")
public class ReconciliationRecord {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_id", nullable = false)
  private Payment payment;

  @Enumerated(EnumType.STRING)
  @Column(name = "before_status", nullable = false, length = 20, updatable = false)
  private PaymentStatus beforeStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "after_status", nullable = false, length = 20, updatable = false)
  private PaymentStatus afterStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_verdict", nullable = false, length = 40, updatable = false)
  private ReconciliationVerdict verdict;

  @Column(name = "note", length = 200, updatable = false)
  private String note;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected ReconciliationRecord() {}

  public ReconciliationRecord(
      Payment payment,
      PaymentStatus beforeStatus,
      PaymentStatus afterStatus,
      ReconciliationVerdict verdict,
      String note) {
    this.id = UUID.randomUUID();
    this.payment = payment;
    this.beforeStatus = beforeStatus;
    this.afterStatus = afterStatus;
    this.verdict = verdict;
    this.note = note;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Payment getPayment() {
    return payment;
  }

  public PaymentStatus getBeforeStatus() {
    return beforeStatus;
  }

  public PaymentStatus getAfterStatus() {
    return afterStatus;
  }

  public ReconciliationVerdict getVerdict() {
    return verdict;
  }

  public String getNote() {
    return note;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
