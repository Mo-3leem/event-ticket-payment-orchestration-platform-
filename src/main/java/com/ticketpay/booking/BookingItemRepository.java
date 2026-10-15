package com.ticketpay.booking;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingItemRepository extends JpaRepository<BookingItem, UUID> {

  List<BookingItem> findByBookingId(UUID bookingId);

  boolean existsByEventSeat_IdAndBooking_Status(UUID eventSeatId, BookingStatus status);
}
