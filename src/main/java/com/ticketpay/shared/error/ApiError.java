package com.ticketpay.shared.error;

import java.time.Instant;
import java.util.List;

public record ApiError(
    Instant timestamp,
    int status,
    String code,
    String message,
    String correlationId,
    List<FieldError> fieldErrors) {

  public record FieldError(String field, String message) {}

  public static ApiError of(int status, String code, String message, String correlationId) {
    return new ApiError(Instant.now(), status, code, message, correlationId, List.of());
  }

  public static ApiError of(
      int status, String code, String message, String correlationId, List<FieldError> fieldErrors) {
    return new ApiError(Instant.now(), status, code, message, correlationId, fieldErrors);
  }
}
