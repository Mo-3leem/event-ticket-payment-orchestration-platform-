package com.ticketpay.payments.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record InitiatePaymentRequest(@NotNull UUID bookingId) {}
