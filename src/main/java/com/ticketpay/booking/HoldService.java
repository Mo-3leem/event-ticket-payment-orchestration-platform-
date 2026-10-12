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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HoldService {

  private final EventSeatRepository eventSeats;
  private final Clock clock;
  private final long holdTtlMinutes;

  public HoldService(
      EventSeatRepository eventSeats,
      Clock clock,
      @Value("${app.hold.ttl-minutes:10}") long holdTtlMinutes) {
    this.eventSeats = eventSeats;
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
    var expired = eventSeats.findByStatusAndHoldExpiresAtBefore(EventSeatStatus.HELD, now);
    // SOLD rows are never touched here: only HELD rows with a past expiry are released.
    // This preserves the Day-1 invariant that an unresolved payment outcome must not
    // lose its seat; payments (Day 3+) will mark seats SOLD before expiry can free them.
    for (EventSeat seat : expired) {
      seat.release();
    }
    eventSeats.saveAll(expired);
    return expired.size();
  }
}
