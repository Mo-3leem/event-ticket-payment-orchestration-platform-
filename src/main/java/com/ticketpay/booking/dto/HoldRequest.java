package com.ticketpay.booking.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record HoldRequest(@NotNull UUID eventSeatId) {}
