package com.ticketpay.payments;

import com.ticketpay.payments.dto.InitiatePaymentRequest;
import com.ticketpay.payments.dto.PaymentResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
@SecurityRequirement(name = "bearerAuth")
public class PaymentController {

  private final PaymentService payments;
  private final ReconciliationService reconciliation;

  public PaymentController(PaymentService payments, ReconciliationService reconciliation) {
    this.payments = payments;
    this.reconciliation = reconciliation;
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

  @GetMapping
  public Page<PaymentResponse> history(
      @RequestParam Optional<String> status,
      Authentication authentication,
      @PageableDefault(size = 20) Pageable pageable) {
    UUID userId = UUID.fromString(authentication.getName());
    return reconciliation.paymentHistory(userId, status.map(this::parseStatus), pageable);
  }

  private PaymentStatus parseStatus(String raw) {
    try {
      return PaymentStatus.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("Invalid status filter: " + raw);
    }
  }
}
