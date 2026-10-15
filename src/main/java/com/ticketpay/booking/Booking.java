package com.ticketpay.booking;

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
@Table(name = "bookings", uniqueConstraints = @UniqueConstraint(columnNames = "reference"))
public class Booking {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID eventId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private BookingStatus status;

  @Column(name = "total_minor", nullable = false, updatable = false)
  private long totalMinor;

  @Column(name = "currency", nullable = false, length = 3, updatable = false)
  private String currency;

  @Column(name = "reference", nullable = false, updatable = false, length = 20, unique = true)
  private String reference;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Booking() {}

  public Booking(UUID userId, UUID eventId, long totalMinor, String currency, String reference, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.eventId = eventId;
    this.status = BookingStatus.PENDING_PAYMENT;
    this.totalMinor = totalMinor;
    this.currency = currency;
    this.reference = reference;
    this.expiresAt = expiresAt;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public UUID getEventId() {
    return eventId;
  }

  public BookingStatus getStatus() {
    return status;
  }

  public long getTotalMinor() {
    return totalMinor;
  }

  public String getCurrency() {
    return currency;
  }

  public String getReference() {
    return reference;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  /**
   * Caller must have verified eligibility (PENDING_PAYMENT, or idempotent CONFIRMED).
   * The IllegalStateException is a programming-error guard; services translate
   * business ineligibility into ConflictException before calling.
   */
  public void confirm() {
    if (status == BookingStatus.CONFIRMED) {
      return;
    }
    if (status != BookingStatus.PENDING_PAYMENT) {
      throw new IllegalStateException("Only PENDING_PAYMENT bookings can be confirmed");
    }
    this.status = BookingStatus.CONFIRMED;
    this.updatedAt = Instant.now();
  }

  public void expire() {
    if (status != BookingStatus.PENDING_PAYMENT) {
      throw new IllegalStateException("Only PENDING_PAYMENT bookings can expire");
    }
    this.status = BookingStatus.EXPIRED;
    this.updatedAt = Instant.now();
  }
}
