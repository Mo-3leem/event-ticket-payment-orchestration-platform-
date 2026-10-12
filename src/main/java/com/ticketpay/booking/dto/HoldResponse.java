package com.ticketpay.booking.dto;

import java.time.Instant;
import java.util.UUID;

public record HoldResponse(UUID eventSeatId, String seatCode, String status, Instant expiresAt) {}
