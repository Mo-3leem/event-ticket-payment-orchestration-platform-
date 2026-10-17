package com.ticketpay.payments.dto;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRecordDto(
    UUID id,
    UUID paymentId,
    String beforeStatus,
    String afterStatus,
    String verdict,
    String note,
    Instant createdAt) {}
