package com.ticketpay.catalog.dto;

import java.time.Instant;
import java.util.UUID;

public record EventDto(
    UUID id, String title, UUID venueId, String venueName, Instant startsAt, long priceMinor, String currency) {}
