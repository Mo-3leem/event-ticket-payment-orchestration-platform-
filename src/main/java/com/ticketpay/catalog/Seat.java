package com.ticketpay.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
    name = "seats",
    uniqueConstraints = @UniqueConstraint(columnNames = {"venue_id", "seat_code"}))
public class Seat {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(name = "seat_code", nullable = false, length = 20)
  private String seatCode;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Seat() {}

  public Seat(Venue venue, String seatCode) {
    this.id = UUID.randomUUID();
    this.venue = venue;
    this.seatCode = seatCode;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Venue getVenue() {
    return venue;
  }

  public String getSeatCode() {
    return seatCode;
  }
}
