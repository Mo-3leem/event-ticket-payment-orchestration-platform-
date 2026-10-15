package com.ticketpay.booking;

import com.ticketpay.booking.dto.HoldResponse;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.shared.error.ConflictException;
import com.ticketpay.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {

  private final EventSeatRepository eventSeats;
  private final BookingItemRepository items;
  private final Clock clock;
  private final long holdTtlMinutes;

  public HoldService(
      EventSeatRepository eventSeats,
      BookingItemRepository items,
      Clock clock,
      @Value("${app.hold.ttl-minutes:10}") long holdTtlMinutes) {
    this.eventSeats = eventSeats;
    this.items = items;
    this.clock = clock;
    this.holdTtlMinutes = holdTtlMinutes;
  }

  @Transactional
  public HoldResponse acquire(UUID eventSeatId, UUID userId) {
    EventSeat seat =
        eventSeats
            .findByIdForUpdate(eventSeatId)
            .orElseThrow(() -> new NotFoundException("Event seat not found"));

    Instant now = clock.instant();

    if (isConsumedByLiveBooking(eventSeatId)) {
      // Owned by a live booking (booking expiry handles it, never this path).
      throw new ConflictException("Seat is not available");
    }

    // A stale HELD row that survived a missed scheduler tick must not block forever.
    // Lazy expiry inside the same locked transaction keeps the invariant without
    // a second transaction or a network call.
    if (seat.getStatus() == EventSeatStatus.HELD && seat.isHoldExpired(now)) {
      seat.release();
    }

    if (seat.getStatus() != EventSeatStatus.AVAILABLE) {
      throw new ConflictException("Seat is not available");
    }

    Instant expiresAt = now.plusSeconds(holdTtlMinutes * 60);
    seat.hold(userId, expiresAt);
    eventSeats.save(seat);

    return new HoldResponse(seat.getId(), seat.getSeat().getSeatCode(), seat.getStatus().name(), expiresAt);
  }

  @Transactional
  public int releaseExpired() {
    Instant now = clock.instant();
    var expired =
        eventSeats.findReleasableHolds(EventSeatStatus.HELD, now, BookingStatus.PENDING_PAYMENT);
    // SOLD rows are never touched here: only HELD rows with a past expiry are released.
    // Seats consumed by a live booking are excluded by the query above; booking
    // expiry owns them. This preserves the invariant that an unresolved payment
    // outcome must not lose its seat.
    for (EventSeat seat : expired) {
      seat.release();
    }
    eventSeats.saveAll(expired);
    return expired.size();
  }

  /**
   * Owner-only release. Anything that is not a live hold of this user — missing row,
   * another user's hold, AVAILABLE, SOLD — maps to 404 so callers learn nothing
   * about other users' inventory. A hold consumed by a live booking is rejected
   * with 409 instead: releasing it would orphan the booking (confirmation would
   * later fail), so booking expiry owns that seat until the booking ends.
   */
  @Transactional
  public void releaseHold(UUID eventSeatId, UUID userId) {
    EventSeat seat =
        eventSeats
            .findByIdForUpdate(eventSeatId)
            .orElseThrow(() -> new NotFoundException("Hold not found"));
    if (seat.getStatus() != EventSeatStatus.HELD || !userId.equals(seat.getHeldByUserId())) {
      throw new NotFoundException("Hold not found");
    }
    if (isConsumedByLiveBooking(eventSeatId)) {
      throw new ConflictException("Seat is already booked");
    }
    seat.release();
  }

  /** Actionable holds only: live HELD rows owned by the user. */
  @Transactional(readOnly = true)
  public Page<HoldResponse> myHolds(UUID userId, Pageable pageable) {
    return eventSeats
        .findByHeldByUserIdAndStatusAndHoldExpiresAtAfter(
            userId, EventSeatStatus.HELD, clock.instant(), pageable)
        .map(
            seat ->
                new HoldResponse(
                    seat.getId(),
                    seat.getSeat().getSeatCode(),
                    seat.getStatus().name(),
                    seat.getHoldExpiresAt()));
  }

  private boolean isConsumedByLiveBooking(UUID eventSeatId) {
    return items.existsByEventSeat_IdAndBooking_Status(eventSeatId, BookingStatus.PENDING_PAYMENT);
  }
}
