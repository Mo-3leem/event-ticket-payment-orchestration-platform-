package com.ticketpay.booking;

import com.ticketpay.catalog.EventSeat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
// Seat exclusivity for live items is enforced by the partial unique index
// uq_booking_items_active_seat (event_seat_id WHERE released = false),
// which also lets seats of EXPIRED bookings be booked again.
@Table(name = "booking_items")
public class BookingItem {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "booking_id", nullable = false)
  private Booking booking;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "event_seat_id", nullable = false)
  private EventSeat eventSeat;

  @Column(name = "released", nullable = false)
  private boolean released = false;

  protected BookingItem() {}

  public BookingItem(Booking booking, EventSeat eventSeat) {
    this.id = UUID.randomUUID();
    this.booking = booking;
    this.eventSeat = eventSeat;
  }

  public UUID getId() {
    return id;
  }

  public Booking getBooking() {
    return booking;
  }

  public EventSeat getEventSeat() {
    return eventSeat;
  }

  public boolean isReleased() {
    return released;
  }

  /** Marks this item historical when its booking expires; the row is kept for audit. */
  public void markReleased() {
    this.released = true;
  }
}
