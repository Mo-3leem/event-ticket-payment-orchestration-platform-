package com.ticketpay.payments;

import com.ticketpay.payments.dto.InitiatePaymentRequest;
import com.ticketpay.payments.dto.PaymentResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class PaymentController {

  private final PaymentService payments;

  public PaymentController(PaymentService payments) {
    this.payments = payments;
  }

  @PostMapping
  public ResponseEntity<PaymentResponse> initiate(
      @Valid @RequestBody InitiatePaymentRequest request,
      @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
      Authentication authentication) {
    UUID userId = UUID.fromString(authentication.getName());
    PaymentService.CompletedPayment completed =
        payments.initiatePayment(userId, request.bookingId(), idempotencyKey);
    return ResponseEntity.status(completed.httpStatus()).body(completed.body());
  }

  @GetMapping("/{id}")
  public PaymentResponse get(@PathVariable UUID id, Authentication authentication) {
    UUID userId = UUID.fromString(authentication.getName());
    return payments.getPayment(userId, id);
  }
}
