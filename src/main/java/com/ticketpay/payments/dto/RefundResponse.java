package com.ticketpay.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record RefundResponse(
    UUID id,
    UUID paymentId,
    long amountMinor,
    String currency,
    String status,
    String providerRefundRef,
    String note,
    UUID triggeredByUserId,
    Instant createdAt,
    Instant updatedAt) {}
