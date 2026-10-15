package com.ticketpay.payments.dto;

import java.util.UUID;

public record PaymentResponse(
    UUID paymentId,
    UUID bookingId,
    String status,
    long amountMinor,
    String currency,
    String providerRef,
    String note) {}
