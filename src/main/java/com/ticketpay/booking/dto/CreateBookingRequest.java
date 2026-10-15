package com.ticketpay.booking.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record CreateBookingRequest(
    @NotNull @Size(min = 1, max = 10) List<@NotNull UUID> holdIds) {}
