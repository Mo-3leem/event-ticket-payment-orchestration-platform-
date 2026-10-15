package com.ticketpay.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "event_seats",
    uniqueConstraints = @UniqueConstraint(columnNames = {"event_id", "seat_id"}))
public class EventSeat {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_id", nullable = false)
  private Event event;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "seat_id", nullable = false)
  private Seat seat;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private EventSeatStatus status;

  @Column(name = "held_by_user_id")
  private UUID heldByUserId;

  @Column(name = "hold_expires_at")
  private Instant holdExpiresAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EventSeat() {}

  public EventSeat(Event event, Seat seat) {
    this.id = UUID.randomUUID();
    this.event = event;
    this.seat = seat;
    this.status = EventSeatStatus.AVAILABLE;
    this.updatedAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Event getEvent() {
    return event;
  }

  public Seat getSeat() {
    return seat;
  }

  public EventSeatStatus getStatus() {
    return status;
  }

  public UUID getHeldByUserId() {
    return heldByUserId;
  }

  public Instant getHoldExpiresAt() {
    return holdExpiresAt;
  }

  public void hold(UUID userId, Instant expiresAt) {
    this.status = EventSeatStatus.HELD;
    this.heldByUserId = userId;
    this.holdExpiresAt = expiresAt;
    this.updatedAt = Instant.now();
  }

  public void release() {
    this.status = EventSeatStatus.AVAILABLE;
    this.heldByUserId = null;
    this.holdExpiresAt = null;
    this.updatedAt = Instant.now();
  }

  public void markSold() {
    this.status = EventSeatStatus.SOLD;
    this.updatedAt = Instant.now();
  }

  public boolean isHoldExpired(Instant now) {
    return status == EventSeatStatus.HELD && holdExpiresAt != null && !holdExpiresAt.isAfter(now);
  }
}
