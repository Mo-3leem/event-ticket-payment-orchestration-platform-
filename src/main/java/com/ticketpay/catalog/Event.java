package com.ticketpay.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "venue_id", nullable = false)
  private Venue venue;

  @Column(name = "title", nullable = false)
  private String title;

  @Column(name = "starts_at", nullable = false)
  private Instant startsAt;

  @Column(name = "price_minor", nullable = false)
  private long priceMinor;

  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected Event() {}

  public Event(Venue venue, String title, Instant startsAt, long priceMinor, String currency) {
    this.id = UUID.randomUUID();
    this.venue = venue;
    this.title = title;
    this.startsAt = startsAt;
    this.priceMinor = priceMinor;
    this.currency = currency;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public Venue getVenue() {
    return venue;
  }

  public String getTitle() {
    return title;
  }

  public Instant getStartsAt() {
    return startsAt;
  }

  public long getPriceMinor() {
    return priceMinor;
  }

  public String getCurrency() {
    return currency;
  }
}
