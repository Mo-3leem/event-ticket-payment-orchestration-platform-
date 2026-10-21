package com.ticketpay.payments;

import com.ticketpay.payments.dto.RefundResponse;
import com.ticketpay.shared.error.ConflictException;
import com.ticketpay.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Refund orchestration for captured (SUCCEEDED) payments — one full refund per
 * payment, executed only by explicit Admin action.
 *
 * <p>Same safety architecture as {@link PaymentService}: Tx1 persists the durable
 * refund intent (stable provider refund reference assigned BEFORE any I/O), the
 * provider call runs outside every transaction and row lock, Tx2 re-locks and
 * re-verifies state before finalizing atomically with the payment transition and
 * the REFUND_COMPLETED audit record.
 *
 * <p>Recovery ({@link #reconcileRefundBatch()}) consults the provider by the SAME
 * refund reference. Definitive outcomes resolve; an empty lookup proves the provider
 * has no record of the refund, so a same-reference re-drive is safe per the
 * documented {@link PaymentProvider#refund} contract; an ambiguous lookup keeps the
 * refund UNKNOWN and is never re-driven. The mock provider demonstrates plumbing
 * only — it cannot prove a real provider's idempotency guarantees.
 */
@Service
public class RefundService {

  private static final Logger log = LoggerFactory.getLogger(RefundService.class);

  /** What actually changed, for batch counting and audit tests. */
  public record FinalizeOutcome(boolean changed, RefundResponse response) {}

  private final PaymentRepository payments;
  private final RefundRepository refunds;
  private final ReconciliationRecordRepository records;
  private final PaymentProvider provider;
  private final Clock clock;
  private final long providerTimeoutMs;
  private final long minAgeMinutes;
  private final int batchSize;
  private final TransactionTemplate tx;
  private final ExecutorService providerCalls;

  public RefundService(
      PaymentRepository payments,
      RefundRepository refunds,
      ReconciliationRecordRepository records,
      PaymentProvider provider,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.refund.provider-timeout-ms:5000}") long providerTimeoutMs,
      @Value("${app.refund.min-age-minutes:10}") long minAgeMinutes,
      @Value("${app.refund.batch-size:50}") int batchSize) {
    this.payments = payments;
    this.refunds = refunds;
    this.records = records;
    this.provider = provider;
    this.clock = clock;
    this.providerTimeoutMs = providerTimeoutMs;
    this.minAgeMinutes = minAgeMinutes;
    this.batchSize = batchSize;
    this.tx = new TransactionTemplate(transactionManager);
    this.providerCalls =
        Executors.newCachedThreadPool(
            r -> {
              Thread thread = new Thread(r, "refund-provider-call");
              thread.setDaemon(true);
              return thread;
            });
  }

  /** Durable snapshot taken under lock; used after Tx1 and for recovery. */
  private record RefundSnapshot(
      UUID refundId,
      UUID paymentId,
      String providerRefundRef,
      String providerChargeRef,
      long amountMinor,
      String currency) {}

  // ---- explicit Admin execution ----

  /**
   * Entry point. No transaction wraps the provider call. A duplicate submission
   * fails in Tx1 on the unique refund row (serialized by the payment row lock) and
   * therefore never launches a second provider operation.
   */
  public RefundResponse initiateRefund(UUID adminId, UUID paymentId, String note) {
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("Refund note is required");
    }
    if (note.trim().length() > 200) {
      throw new IllegalArgumentException("Refund note must be at most 200 characters");
    }
    RefundSnapshot snapshot = createIntent(adminId, paymentId, note.trim());
    PaymentService.ProviderCall call = invokeRefund(snapshot);
    return finalizeTx(snapshot.refundId(), call).response();
  }

  /** Tx1: lock payment, validate, persist refund intent with its stable reference. */
  private RefundSnapshot createIntent(UUID adminId, UUID paymentId, String note) {
    try {
      return tx.execute(
          status -> {
            Payment payment =
                payments
                    .findByIdForUpdate(paymentId)
                    .orElseThrow(() -> new NotFoundException("Payment not found"));
            if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
              throw new ConflictException(
                  "Only captured (SUCCEEDED) payments can be refunded; payment is "
                      + payment.getStatus());
            }
            if (refunds.findByPaymentId(paymentId).isPresent()) {
              throw new ConflictException("Refund already exists for payment");
            }
            Refund refund = new Refund(payment, adminId, note);
            refunds.saveAndFlush(refund);
            return new RefundSnapshot(
                refund.getId(),
                payment.getId(),
                refund.getProviderRefundRef(),
                payment.getProviderRef(),
                refund.getAmountMinor(),
                refund.getCurrency());
          });
    } catch (DataIntegrityViolationException duplicate) {
      // The payment row lock already serializes initiators, so this is belt-and-braces
      // for the UNIQUE(payment_id) race — reported as the documented conflict.
      throw new ConflictException("Refund already exists for payment");
    }
  }

  // ---- Tx2: idempotent finalization (shared by live call and reconciliation) ----

  /**
   * Locks the refund, re-verifies it is still in flight, then finalizes atomically:
   * refund transition, payment REFUNDED (definitive success only), and the single
   * REFUND_COMPLETED record — all in one transaction. A refund that is already
   * terminal returns its current state without writing anything, so crash recovery
   * and repeated reconciliation can never duplicate audit records.
   */
  private FinalizeOutcome finalizeTx(UUID refundId, PaymentService.ProviderCall call) {
    return tx.execute(
        status -> {
          Refund refund =
              refunds.findByIdForUpdate(refundId).orElseThrow(() -> new NotFoundException("Refund not found"));
          if (!refund.isInFlight()) {
            return new FinalizeOutcome(false, toDto(refund));
          }
          Payment payment =
              payments
                  .findByIdForUpdate(refund.getPayment().getId())
                  .orElseThrow(() -> new NotFoundException("Payment not found"));
          // Defensive truth checks: the ledger must match the captured charge exactly.
          if (refund.getAmountMinor() != payment.getAmountMinor()
              || !refund.getCurrency().equals(payment.getCurrency())) {
            throw new IllegalStateException(
                "Refund amount/currency does not match the captured payment");
          }
          if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException(
                "Payment is no longer refundable; payment is " + payment.getStatus());
          }
          Instant now = clock.instant();
          switch (call.outcome()) {
            case SUCCESS -> {
              refund.succeed(now);
              payment.markRefunded();
              records.save(
                  new ReconciliationRecord(
                      payment,
                      PaymentStatus.SUCCEEDED,
                      PaymentStatus.REFUNDED,
                      ReconciliationVerdict.REFUND_COMPLETED,
                      "Refund " + refund.getProviderRefundRef()));
            }
            case DECLINED -> refund.fail(now);
            case TIMEOUT, ERROR -> refund.markUnknown(now);
          }
          return new FinalizeOutcome(true, toDto(refund));
        });
  }

  // ---- provider invocation (never inside a transaction) ----

  private PaymentService.ProviderCall invokeRefund(RefundSnapshot snapshot) {
    long start = clock.millis();
    Future<PaymentProvider.ChargeResult> future =
        providerCalls.submit(
            () ->
                provider.refund(
                    snapshot.providerRefundRef(),
                    snapshot.providerChargeRef(),
                    snapshot.amountMinor(),
                    snapshot.currency()));
    try {
      PaymentProvider.ChargeResult result = future.get(providerTimeoutMs, TimeUnit.MILLISECONDS);
      long latency = clock.millis() - start;
      if (result.success()) {
        return new PaymentService.ProviderCall(AttemptOutcome.SUCCESS, latency, null);
      }
      return new PaymentService.ProviderCall(AttemptOutcome.DECLINED, latency, result.declineCode());
    } catch (TimeoutException timeout) {
      future.cancel(true);
      return new PaymentService.ProviderCall(AttemptOutcome.TIMEOUT, providerTimeoutMs, "REFUND_TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.interrupted();
      future.cancel(true);
      return new PaymentService.ProviderCall(
          AttemptOutcome.ERROR, clock.millis() - start, "INTERRUPTED");
    } catch (java.util.concurrent.ExecutionException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      if (cause instanceof ProviderCallAbortedException) {
        return new PaymentService.ProviderCall(
            AttemptOutcome.ERROR, clock.millis() - start, "ABORTED");
      }
      return new PaymentService.ProviderCall(
          AttemptOutcome.ERROR, clock.millis() - start, cause.getClass().getSimpleName());
    }
  }

  // ---- reconciliation of aged REQUESTED/UNKNOWN refunds (no automatic execution
  //      of new refunds; recovery of already-stated intent only) ----

  /**
   * One scheduler cycle over aged in-flight refunds. Each refund resolves in its own
   * transaction; entry point itself runs without one.
   *
   * @return number of refunds whose state actually changed.
   */
  public int reconcileRefundBatch() {
    Instant cutoff = clock.instant().minusSeconds(minAgeMinutes * 60);
    List<Refund> batch =
        tx.execute(
            status ->
                refunds.findReconciliable(
                    List.of(RefundStatus.REQUESTED, RefundStatus.UNKNOWN),
                    cutoff,
                    PageRequest.of(0, batchSize)));
    int resolved = 0;
    for (Refund candidate : batch) {
      try {
        if (reconcileOne(candidate.getId())) {
          resolved++;
        }
      } catch (RuntimeException failure) {
        // Per-item isolation: a poisoned refund row (corrupt ledger, vanished
        // payment, provider contract violation) must not stall the rest of the batch.
        log.warn(
            "Refund reconciliation failed for {}: {}",
            candidate.getId(),
            failure.toString());
        try {
          touchRefund(candidate.getId());
        } catch (RuntimeException ignored) {
          // Next cycle retries anyway; the batch continues regardless.
        }
      }
    }
    return resolved;
  }

  private boolean reconcileOne(UUID refundId) {
    RefundSnapshot snapshot =
        tx.execute(
            status -> {
              Refund refund = refunds.findByIdForUpdate(refundId).orElse(null);
              if (refund == null || !refund.isInFlight()) {
                return null;
              }
              Payment payment =
                  payments.findById(refund.getPayment().getId()).orElse(null);
              if (payment == null) {
                return null;
              }
              return new RefundSnapshot(
                  refund.getId(),
                  payment.getId(),
                  refund.getProviderRefundRef(),
                  payment.getProviderRef(),
                  refund.getAmountMinor(),
                  refund.getCurrency());
            });
    if (snapshot == null) {
      return false;
    }
    Optional<PaymentProvider.ChargeResult> known;
    try {
      known = provider.lookupRefund(snapshot.providerRefundRef());
    } catch (ProviderStatusUnknownException ambiguous) {
      touchRefund(refundId);
      return false;
    }
    if (known.isEmpty()) {
      // Documented contract: an empty lookup proves the provider has NO record of
      // this refund — nothing was refunded — and refund() deduplicates by the SAME
      // reference, so re-driving cannot refund twice. (PaymentProvider.lookupRefund.)
      PaymentService.ProviderCall call = invokeRefund(snapshot);
      return finalizeTx(refundId, call).changed();
    }
    PaymentProvider.ChargeResult verdict = known.get();
    PaymentService.ProviderCall call =
        verdict.success()
            ? new PaymentService.ProviderCall(AttemptOutcome.SUCCESS, 0, "RECOVERED_VIA_LOOKUP")
            : new PaymentService.ProviderCall(AttemptOutcome.DECLINED, 0, verdict.declineCode());
    return finalizeTx(refundId, call).changed();
  }

  private void touchRefund(UUID refundId) {
    tx.execute(
        status -> {
          refunds
              .findByIdForUpdate(refundId)
              .filter(Refund::isInFlight)
              .ifPresent(r -> r.touch(clock.instant()));
          return null;
        });
  }

  // ---- reads ----

  @Transactional(readOnly = true)
  public Page<com.ticketpay.payments.dto.PaymentResponse> refundRequired(Pageable pageable) {
    return payments
        .findRefundRequired(PaymentStatus.SUCCEEDED, pageable)
        .map(
            p ->
                new com.ticketpay.payments.dto.PaymentResponse(
                    p.getId(),
                    p.getBookingId(),
                    p.getStatus().name(),
                    p.getAmountMinor(),
                    p.getCurrency(),
                    p.getProviderRef(),
                    p.getNote()));
  }

  @Transactional(readOnly = true)
  public List<RefundResponse> refundHistory(UUID paymentId) {
    if (payments.findById(paymentId).isEmpty()) {
      throw new NotFoundException("Payment not found");
    }
    return refunds.findByPaymentIdOrderByCreatedAtDescIdDesc(paymentId).stream()
        .map(this::toDto)
        .toList();
  }

  private RefundResponse toDto(Refund refund) {
    return new RefundResponse(
        refund.getId(),
        refund.getPayment().getId(),
        refund.getAmountMinor(),
        refund.getCurrency(),
        refund.getStatus().name(),
        refund.getProviderRefundRef(),
        refund.getNote(),
        refund.getTriggeredByUserId(),
        refund.getCreatedAt(),
        refund.getUpdatedAt());
  }
}
