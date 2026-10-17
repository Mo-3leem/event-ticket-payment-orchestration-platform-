package com.ticketpay.payments.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReviewNoteRequest(@NotBlank @Size(max = 200) String note) {}
