package com.ticketpay.booking.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BookingResponse(
    UUID bookingId,
    String reference,
    String status,
    long totalMinor,
    String currency,
    Instant expiresAt,
    List<UUID> eventSeatIds) {}
