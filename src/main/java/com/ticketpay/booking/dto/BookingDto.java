package com.ticketpay.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record BookingDto(
    UUID bookingId, String reference, String status, long totalMinor, String currency, Instant expiresAt) {}
