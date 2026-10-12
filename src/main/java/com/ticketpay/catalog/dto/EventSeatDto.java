package com.ticketpay.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record EventSeatDto(
    UUID eventSeatId, String seatCode, String status, UUID heldByUserId, Instant holdExpiresAt) {}
