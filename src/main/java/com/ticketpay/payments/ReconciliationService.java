package com.ticketpay.payments;

import com.ticketpay.booking.Booking;
import com.ticketpay.booking.BookingRepository;
import com.ticketpay.booking.BookingService;
import com.ticketpay.booking.BookingStatus;
import com.ticketpay.payments.dto.PaymentResponse;
import com.ticketpay.payments.dto.ReconciliationRecordDto;
import com.ticketpay.shared.error.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Scheduled recovery for ambiguous payments. Resolves only definitive provider
 * outcomes; anything ambiguous stays UNKNOWN. Each payment is processed in its own
 * transaction, and every state change is re-verified under a fresh row lock, so
 * concurrent workers, live payment completions, and booking expiry cannot produce
 * duplicate transitions or inconsistent audit records.
 *
 * <p>Reconciliation records are append-only: they are written exactly once per
 * transition (or manual review) and never updated or deleted by this service.
 */
@Service
public class ReconciliationService {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

  private final PaymentRepository payments;
  private final ReconciliationRecordRepository records;
  private final BookingRepository bookings;
  private final BookingService bookingService;
  private final PaymentProvider provider;
  private final Clock clock;
  private final long minAgeMinutes;
  private final int batchSize;
  private final TransactionTemplate tx;

  public ReconciliationService(
      PaymentRepository payments,
      ReconciliationRecordRepository records,
      BookingRepository bookings,
      BookingService bookingService,
      PaymentProvider provider,
      Clock clock,
      PlatformTransactionManager transactionManager,
      @Value("${app.reconciliation.min-age-minutes:10}") long minAgeMinutes,
      @Value("${app.reconciliation.batch-size:50}") int batchSize) {
    this.payments = payments;
    this.records = records;
    this.bookings = bookings;
    this.bookingService = bookingService;
    this.provider = provider;
    this.clock = clock;
    this.minAgeMinutes = minAgeMinutes;
    this.batchSize = batchSize;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * One scheduler cycle. Entry point runs without a transaction; every payment is
   * reconciled in its own transaction so one failure cannot roll back the batch.
   *
   * @return number of payments whose state actually changed.
   */
  public int reconcileBatch() {
    Instant cutoff = clock.instant().minusSeconds(minAgeMinutes * 60);
    // Candidate discovery runs in its own short transaction (locks release at
    // commit); correctness rests on per-item re-verification under fresh locks.
    List<Payment> batch =
        tx.execute(
            status ->
                payments.findReconciliable(
                    PaymentStatus.UNKNOWN, cutoff, PageRequest.of(0, batchSize)));
    int resolved = 0;
    for (Payment candidate : batch) {
      try {
        if (reconcileOne(candidate.getId())) {
          resolved++;
        }
      } catch (RuntimeException failure) {
        // Per-item isolation: one malformed or conflicting row must not abort the
        // rest of the batch. Back off the item so the same row is not hammered.
        log.warn(
            "Reconciliation failed for payment {}: {}",
            candidate.getId(),
            failure.toString());
        try {
          touchChecked(candidate.getId());
        } catch (RuntimeException ignored) {
          // Next cycle retries anyway; the batch continues regardless.
        }
      }
    }
    return resolved;
  }

  /**
   * Appends a manual review note. Moves no money, changes no payment or booking
   * state — the record itself is the entire effect.
   */
  @Transactional
  public ReconciliationRecordDto reviewPayment(UUID paymentId, String note) {
    if (note == null || note.isBlank()) {
      throw new IllegalArgumentException("Review note is required");
    }
    if (note.trim().length() > 200) {
      throw new IllegalArgumentException("Review note must be at most 200 characters");
    }
    Payment payment =
        payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment not found"));
    ReconciliationRecord record =
        records.save(
            new ReconciliationRecord(
                payment,
                payment.getStatus(),
                payment.getStatus(),
                ReconciliationVerdict.MANUAL_REVIEW,
                note.trim()));
    return toDto(record);
  }

  @Transactional(readOnly = true)
  public Page<PaymentResponse> paymentHistory(
      UUID userId, Optional<PaymentStatus> status, Pageable pageable) {
    Page<Payment> page =
        status
            .map(s -> payments.findByUserIdAndStatusOrderByCreatedAtDescIdDesc(userId, s, pageable))
            .orElseGet(() -> payments.findByUserIdOrderByCreatedAtDescIdDesc(userId, pageable));
    return page.map(this::toPaymentDto);
  }

  @Transactional(readOnly = true)
  public Page<PaymentResponse> adminPayments(Optional<PaymentStatus> status, Pageable pageable) {
    Page<Payment> page =
        status
            .map(s -> payments.findByStatusOrderByCreatedAtDescIdDesc(s, pageable))
            .orElseGet(() -> payments.findAllByOrderByCreatedAtDescIdDesc(pageable));
    return page.map(this::toPaymentDto);
  }

  @Transactional(readOnly = true)
  public Page<ReconciliationRecordDto> recordHistory(Optional<UUID> paymentId, Pageable pageable) {
    Page<ReconciliationRecord> page =
        paymentId
            .map(id -> records.findByPaymentIdOrderByCreatedAtDescIdDesc(id, pageable))
            .orElseGet(() -> records.findAllByOrderByCreatedAtDescIdDesc(pageable));
    return page.map(this::toDto);
  }

  // ---- per-payment recovery (own transaction each) ----

  private boolean reconcileOne(UUID paymentId) {
    Snapshot snapshot =
        tx.execute(
            status -> {
              Payment payment =
                  payments.findByIdForUpdate(paymentId).orElse(null);
              if (payment == null || payment.getStatus() != PaymentStatus.UNKNOWN) {
                return null;
              }
              return new Snapshot(
                  payment.getId(), payment.getBookingId(), payment.getProviderRef());
            });
    if (snapshot == null) {
      return false;
    }
    Optional<PaymentProvider.ChargeResult> known;
    try {
      known = provider.lookup(snapshot.providerRef());
    } catch (ProviderStatusUnknownException ambiguous) {
      touchChecked(snapshot.paymentId());
      return false;
    }
    if (known.isEmpty()) {
      touchChecked(snapshot.paymentId());
      return false;
    }
    PaymentProvider.ChargeResult verdict = known.get();
    return tx.execute(
        status -> {
          Payment payment =
              payments.findByIdForUpdate(snapshot.paymentId()).orElse(null);
          if (payment == null || payment.getStatus() != PaymentStatus.UNKNOWN) {
            // A live completion or another worker resolved it first: nothing to do,
            // and crucially no second audit record.
            return false;
          }
          payment.touchReconciled(clock.instant());
          if (verdict.success()) {
            payment.succeed();
            Booking booking =
                bookings
                    .findByIdForUpdate(payment.getBookingId())
                    .orElseThrow(() -> new NotFoundException("Booking not found"));
            if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
              bookingService.confirmBooking(booking.getId());
              records.save(
                  new ReconciliationRecord(
                      payment,
                      PaymentStatus.UNKNOWN,
                      PaymentStatus.SUCCEEDED,
                      ReconciliationVerdict.PROVIDER_SUCCESS,
                      null));
            } else {
              payment.annotate("BOOKING_EXPIRED_AT_CONFIRM");
              records.save(
                  new ReconciliationRecord(
                      payment,
                      PaymentStatus.UNKNOWN,
                      PaymentStatus.SUCCEEDED,
                      ReconciliationVerdict.PROVIDER_SUCCESS_BOOKING_EXPIRED,
                      "Booking " + booking.getStatus()));
            }
          } else {
            payment.fail(verdict.declineCode());
            records.save(
                new ReconciliationRecord(
                    payment,
                    PaymentStatus.UNKNOWN,
                    PaymentStatus.FAILED,
                    ReconciliationVerdict.PROVIDER_DECLINED,
                    verdict.declineCode()));
          }
          return true;
        });
  }

  private void touchChecked(UUID paymentId) {
    tx.execute(
        status -> {
          payments
              .findByIdForUpdate(paymentId)
              .filter(p -> p.getStatus() == PaymentStatus.UNKNOWN)
              .ifPresent(p -> p.touchReconciled(clock.instant()));
          return null;
        });
  }

  private record Snapshot(UUID paymentId, UUID bookingId, String providerRef) {}

  private PaymentResponse toPaymentDto(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        payment.getBookingId(),
        payment.getStatus().name(),
        payment.getAmountMinor(),
        payment.getCurrency(),
        payment.getProviderRef(),
        payment.getNote());
  }

  private ReconciliationRecordDto toDto(ReconciliationRecord record) {
    return new ReconciliationRecordDto(
        record.getId(),
        record.getPayment().getId(),
        record.getBeforeStatus().name(),
        record.getAfterStatus().name(),
        record.getVerdict().name(),
        record.getNote(),
        record.getCreatedAt());
  }
}
