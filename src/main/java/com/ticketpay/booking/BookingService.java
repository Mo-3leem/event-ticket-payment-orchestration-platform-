package com.ticketpay.booking;

import com.ticketpay.booking.dto.BookingDto;
import com.ticketpay.booking.dto.BookingResponse;
import com.ticketpay.catalog.Event;
import com.ticketpay.catalog.EventRepository;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.shared.error.ConflictException;
import com.ticketpay.shared.error.NotFoundException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {

  static final int MAX_SEATS_PER_BOOKING = 10;

  private static final String REFERENCE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

  private final BookingRepository bookings;
  private final BookingItemRepository items;
  private final EventSeatRepository eventSeats;
  private final EventRepository events;
  private final Clock clock;
  private final long holdTtlMinutes;
  private final SecureRandom random = new SecureRandom();

  public BookingService(
      BookingRepository bookings,
      BookingItemRepository items,
      EventSeatRepository eventSeats,
      EventRepository events,
      Clock clock,
      @Value("${app.hold.ttl-minutes:10}") long holdTtlMinutes) {
    this.bookings = bookings;
    this.items = items;
    this.eventSeats = eventSeats;
    this.events = events;
    this.clock = clock;
    this.holdTtlMinutes = holdTtlMinutes;
  }

  @Transactional
  public BookingResponse createBooking(UUID userId, List<UUID> eventSeatIds) {
    if (eventSeatIds == null || eventSeatIds.isEmpty()) {
      throw new IllegalArgumentException("At least one hold is required");
    }
    if (eventSeatIds.size() > MAX_SEATS_PER_BOOKING) {
      throw new IllegalArgumentException("At most " + MAX_SEATS_PER_BOOKING + " seats per booking");
    }
    List<UUID> distinct = eventSeatIds.stream().distinct().toList();
    if (distinct.size() != eventSeatIds.size()) {
      throw new IllegalArgumentException("Duplicate hold IDs are not allowed");
    }

    // Deterministic lock order (sorted IDs) so concurrent creations cannot deadlock.
    List<UUID> ordered = distinct.stream().sorted().toList();
    Instant now = clock.instant();

    List<EventSeat> seats = new ArrayList<>(ordered.size());
    for (UUID seatId : ordered) {
      EventSeat seat =
          eventSeats
              .findByIdForUpdate(seatId)
              .orElseThrow(() -> new NotFoundException("Event seat not found"));
      requireConsumableHold(seat, userId, now);
      seats.add(seat);
    }

    UUID eventId = seats.get(0).getEvent().getId();
    for (EventSeat seat : seats) {
      if (!eventId.equals(seat.getEvent().getId())) {
        throw new IllegalArgumentException("All holds must belong to the same event");
      }
    }

    Event event =
        events.findById(eventId).orElseThrow(() -> new NotFoundException("Event not found"));
    long total = seats.size() * event.getPriceMinor();

    Booking booking =
        new Booking(
            userId,
            eventId,
            total,
            event.getCurrency(),
            newReference(),
            now.plusSeconds(holdTtlMinutes * 60));
    bookings.save(booking);

    List<BookingItem> bookingItems = seats.stream().map(seat -> new BookingItem(booking, seat)).toList();
    try {
      items.saveAll(bookingItems);
      items.flush();
    } catch (DataIntegrityViolationException ex) {
      // UNIQUE(event_seat_id): a concurrent creation consumed one of these holds first.
      throw new ConflictException("Seat is already booked");
    }

    return toResponse(booking, ordered);
  }

  /**
   * Internal operation only — no public endpoint. The payment module invokes this
   * after a verified terminal success. If the booking deadline has already passed,
   * it expires and releases the seats instead of confirming them, returning an
   * EXPIRED response so the caller can flag the captured payment for refund.
   * Idempotent: confirming an already-CONFIRMED booking succeeds without changes.
   */
  @Transactional
  public BookingResponse confirmBooking(UUID bookingId) {
    Booking booking =
        bookings
            .findByIdForUpdate(bookingId)
            .orElseThrow(() -> new NotFoundException("Booking not found"));
    if (booking.getStatus() == BookingStatus.CONFIRMED) {
      return toResponse(booking, seatIdsOf(bookingId));
    }
    if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
      throw new ConflictException("Booking cannot be confirmed");
    }
    if (!booking.getExpiresAt().isAfter(clock.instant())) {
      expireLocked(booking);
      return toResponse(booking, seatIdsOf(bookingId));
    }

    List<EventSeat> seats = lockedSeatsOf(bookingId);
    for (EventSeat seat : seats) {
      if (seat.getStatus() != EventSeatStatus.HELD) {
        throw new ConflictException("Seat is no longer available");
      }
    }

    booking.confirm();
    for (EventSeat seat : seats) {
      seat.markSold();
    }
    return toResponse(booking, seatIdsOf(seats));
  }

  /**
   * Releases seats of unpaid bookings past their deadline and marks them EXPIRED.
   * Only HELD seats are released — SOLD seats are never touched here. The booking's
   * items are marked released in the same transaction so the seats become bookable
   * again (partial unique index covers only live items) while history is preserved.
   * Confirmed bookings are never selected here, so their items stay live forever.
   */
  @Transactional
  public int expireBookings() {
    Instant now = clock.instant();
    List<Booking> expired = bookings.findByStatusAndExpiresAtBefore(BookingStatus.PENDING_PAYMENT, now);
    int count = 0;
    for (Booking booking : expired) {
      expireLocked(booking);
      count++;
    }
    return count;
  }

  /** Caller already holds the booking row lock. Locks seats in deterministic order. */
  private void expireLocked(Booking booking) {
    List<BookingItem> ownedItems = items.findByBookingId(booking.getId());
    List<EventSeat> seats = lockSeats(ownedItems);
    for (EventSeat seat : seats) {
      if (seat.getStatus() == EventSeatStatus.HELD) {
        seat.release();
      }
    }
    for (BookingItem item : ownedItems) {
      item.markReleased();
    }
    booking.expire();
  }

  @Transactional(readOnly = true)
  public Page<BookingDto> myBookings(UUID userId, Pageable pageable) {
    return bookings
        .findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable)
        .map(
            b ->
                new BookingDto(
                    b.getId(),
                    b.getReference(),
                    b.getStatus().name(),
                    b.getTotalMinor(),
                    b.getCurrency(),
                    b.getExpiresAt()));
  }

  /** A hold is consumable only if this user still owns a live HELD row for it. */
  private void requireConsumableHold(EventSeat seat, UUID userId, Instant now) {
    if (seat.getStatus() == EventSeatStatus.HELD && seat.isHoldExpired(now)) {
      // Never revive an expired hold into a booking. The row stays for the
      // hold-acquisition / expiry paths to clean up; this request just fails.
      throw new ConflictException("Hold has expired");
    }
    if (seat.getStatus() != EventSeatStatus.HELD || !userId.equals(seat.getHeldByUserId())) {
      throw new ConflictException("Seat is not available");
    }
    if (isConsumedByLiveBooking(seat.getId())) {
      throw new ConflictException("Seat is already booked");
    }
  }

  private boolean isConsumedByLiveBooking(UUID eventSeatId) {
    return items.existsByEventSeat_IdAndBooking_Status(eventSeatId, BookingStatus.PENDING_PAYMENT);
  }

  private List<EventSeat> lockedSeatsOf(UUID bookingId) {
    List<BookingItem> owned = items.findByBookingId(bookingId);
    return lockSeats(owned);
  }

  /** Locks seats in deterministic sorted order so concurrent operations cannot deadlock. */
  private List<EventSeat> lockSeats(List<BookingItem> ownedItems) {
    List<UUID> seatIds =
        ownedItems.stream().map(item -> item.getEventSeat().getId()).sorted().toList();
    List<EventSeat> seats = new ArrayList<>(seatIds.size());
    for (UUID seatId : seatIds) {
      seats.add(
          eventSeats
              .findByIdForUpdate(seatId)
              .orElseThrow(() -> new NotFoundException("Event seat not found")));
    }
    return seats;
  }

  private List<UUID> seatIdsOf(UUID bookingId) {
    return items.findByBookingId(bookingId).stream()
        .map(item -> item.getEventSeat().getId())
        .sorted()
        .toList();
  }

  private List<UUID> seatIdsOf(List<EventSeat> seats) {
    return seats.stream().map(EventSeat::getId).sorted().toList();
  }

  private BookingResponse toResponse(Booking booking, List<UUID> eventSeatIds) {
    return new BookingResponse(
        booking.getId(),
        booking.getReference(),
        booking.getStatus().name(),
        booking.getTotalMinor(),
        booking.getCurrency(),
        booking.getExpiresAt(),
        eventSeatIds);
  }

  private String newReference() {
    for (; ; ) {
      StringBuilder sb = new StringBuilder("BK-");
      for (int i = 0; i < 8; i++) {
        sb.append(REFERENCE_ALPHABET.charAt(random.nextInt(REFERENCE_ALPHABET.length())));
      }
      String reference = sb.toString();
      if (!bookings.existsByReference(reference)) {
        return reference;
      }
    }
  }
}
