package com.ticketpay.catalog;

import com.ticketpay.booking.BookingStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface EventSeatRepository extends JpaRepository<EventSeat, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select es from EventSeat es where es.id = :id")
  Optional<EventSeat> findByIdForUpdate(UUID id);

  List<EventSeat> findByEventIdOrderBySeatSeatCodeAsc(UUID eventId);

  @Query(
      "select es from EventSeat es where es.heldByUserId = :userId"
          + " and es.status = :status and es.holdExpiresAt > :now"
          + " and es.id not in (select bi.eventSeat.id from BookingItem bi"
          + " where bi.booking.status = :bookedStatus)"
          + " order by es.holdExpiresAt asc, es.id asc")
  Page<EventSeat> findActionableHolds(
      UUID userId,
      EventSeatStatus status,
      Instant now,
      BookingStatus bookedStatus,
      Pageable pageable);

  /**
   * Releasable holds only: expired HELD rows NOT consumed by a live booking.
   * Seats of PENDING_PAYMENT bookings are owned by booking expiry, never by this query.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select es from EventSeat es where es.status = :status and es.holdExpiresAt < :cutoff"
          + " and es.id not in (select bi.eventSeat.id from BookingItem bi"
          + " where bi.booking.status = :bookedStatus)")
  List<EventSeat> findReleasableHolds(
      EventSeatStatus status, Instant cutoff, BookingStatus bookedStatus);
}
