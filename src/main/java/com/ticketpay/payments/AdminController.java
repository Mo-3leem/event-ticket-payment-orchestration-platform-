package com.ticketpay.payments;

import com.ticketpay.payments.dto.PaymentResponse;
import com.ticketpay.payments.dto.ReconciliationRecordDto;
import com.ticketpay.payments.dto.RefundResponse;
import com.ticketpay.payments.dto.ReviewNoteRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@SecurityRequirement(name = "bearerAuth")
public class AdminController {

  private final ReconciliationService reconciliation;
  private final RefundService refunds;

  public AdminController(ReconciliationService reconciliation, RefundService refunds) {
    this.reconciliation = reconciliation;
    this.refunds = refunds;
  }

  @GetMapping("/payments")
  public Page<PaymentResponse> payments(
      @RequestParam Optional<String> status, @PageableDefault(size = 20) Pageable pageable) {
    return reconciliation.adminPayments(status.map(this::parseStatus), pageable);
  }

  @GetMapping("/payments/refund-required")
  public Page<PaymentResponse> refundRequired(@PageableDefault(size = 20) Pageable pageable) {
    return refunds.refundRequired(pageable);
  }

  @PostMapping("/payments/{paymentId}/refund")
  @ResponseStatus(HttpStatus.CREATED)
  public RefundResponse refund(
      @PathVariable UUID paymentId, @Valid @RequestBody ReviewNoteRequest request) {
    UUID adminId = adminId();
    return refunds.initiateRefund(adminId, paymentId, request.note());
  }

  @GetMapping("/payments/{paymentId}/refunds")
  public List<RefundResponse> refundHistory(@PathVariable UUID paymentId) {
    return refunds.refundHistory(paymentId);
  }

  /** Initiator identity comes from the authenticated principal — never the body. */
  private UUID adminId() {
    Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    return UUID.fromString(authentication.getName());
  }

  @GetMapping("/reconciliation")
  public Page<ReconciliationRecordDto> records(
      @RequestParam Optional<UUID> paymentId, @PageableDefault(size = 20) Pageable pageable) {
    return reconciliation.recordHistory(paymentId, pageable);
  }

  @PostMapping("/reconciliation/{paymentId}/review")
  @ResponseStatus(HttpStatus.CREATED)
  public ReconciliationRecordDto review(
      @PathVariable UUID paymentId, @Valid @RequestBody ReviewNoteRequest request) {
    return reconciliation.reviewPayment(paymentId, request.note());
  }

  private PaymentStatus parseStatus(String raw) {
    try {
      return PaymentStatus.valueOf(raw.toUpperCase());
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException("Invalid status filter: " + raw);
    }
  }
}
