package com.ticketpay.payments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.booking.Booking;
import com.ticketpay.booking.BookingRepository;
import com.ticketpay.booking.BookingService;
import com.ticketpay.booking.BookingStatus;
import com.ticketpay.booking.dto.BookingResponse;
import com.ticketpay.payments.dto.PaymentResponse;
import com.ticketpay.shared.error.ConflictException;
import com.ticketpay.shared.error.NotFoundException;
import com.ticketpay.shared.error.UnprocessableException;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payment orchestration. Owns payment state, idempotency, and recovery; knows the
 * provider only through {@link PaymentProvider}. The provider is NEVER called inside
 * a database transaction: Tx1 prepares durable intent, the call happens outside any
 * transaction, Tx2 records the outcome atomically with booking confirmation.
 *
 * <p>Guarantees (and limits): no double-booking, no lost seat transitions, every
 * ambiguous charge lands in UNKNOWN with a full audit trail. Exactly-once external
 * charging is NOT claimed — safety rests on idempotency keys, the unique payment per
 * booking, and provider-side deduplication by stable reference.
 */
@Service
public class PaymentService {

  /** Internal completion: body plus the HTTP status the controller must return. */
  public record CompletedPayment(PaymentResponse body, int httpStatus) {}

  /** Internal signal: concurrent key insert won; retry the whole begin step in a fresh transaction. */
  private static final class KeyWriteConflict extends RuntimeException {}

  private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

  private BeginOutcome beginInFreshTransaction(UUID userId, UUID bookingId, String idemKey, String fingerprint) {
    try {
      return tx.execute(status -> beginAttempt(userId, bookingId, idemKey, fingerprint));
    } catch (KeyWriteConflict firstAttempt) {
      // The winner's row is now visible (or still blocks until it commits);
      // a second conflict means a live operation really owns the key.
      try {
        return tx.execute(status -> beginAttempt(userId, bookingId, idemKey, fingerprint));
      } catch (KeyWriteConflict secondAttempt) {
        throw new ConflictException("Payment already in progress");
      }
    }
  }

  /** A provider interaction decoupled from transport, so tests can drive recovery. */
  public record ProviderCall(AttemptOutcome outcome, long latencyMs, String errorCode) {}

  private final PaymentRepository payments;
  private final PaymentAttemptRepository attempts;
  private final IdempotencyKeyRepository keys;
  private final BookingRepository bookings;
  private final BookingService bookingService;
  private final PaymentProvider provider;
  private final Clock clock;
  private final ObjectMapper objectMapper;
  private final long providerTimeoutMs;
  private final long keyTtlHours;
  private final long reclaimAfterSeconds;
  private final ExecutorService providerCalls;
  /**
   * Programmatic transactions for steps invoked via self-calls. Spring's @Transactional
   * is proxy-based and silently ignored on self-invocation, which would split Tx1/Tx2
   * into autocommit statements; the template guarantees the intended boundaries.
   */
  private final TransactionTemplate tx;

  public PaymentService(
      PaymentRepository payments,
      PaymentAttemptRepository attempts,
      IdempotencyKeyRepository keys,
      BookingRepository bookings,
      BookingService bookingService,
      PaymentProvider provider,
      Clock clock,
      ObjectMapper objectMapper,
      PlatformTransactionManager transactionManager,
      @Value("${app.payment.provider-timeout-ms:5000}") long providerTimeoutMs,
      @Value("${app.payment.key-ttl-hours:24}") long keyTtlHours,
      @Value("${app.payment.reclaim-after-seconds:120}") long reclaimAfterSeconds) {
    this.payments = payments;
    this.attempts = attempts;
    this.keys = keys;
    this.bookings = bookings;
    this.bookingService = bookingService;
    this.provider = provider;
    this.clock = clock;
    this.objectMapper = objectMapper;
    this.providerTimeoutMs = providerTimeoutMs;
    this.keyTtlHours = keyTtlHours;
    this.reclaimAfterSeconds = reclaimAfterSeconds;
    this.tx = new TransactionTemplate(transactionManager);
    this.providerCalls =
        Executors.newCachedThreadPool(
            r -> {
              Thread thread = new Thread(r, "provider-call");
              thread.setDaemon(true);
              return thread;
            });
  }

  @PreDestroy
  void shutdown() {
    providerCalls.shutdownNow();
  }

  /**
   * Entry point. No transaction here: Tx1 prepares intent, the provider call runs
   * outside any transaction, Tx2 records the outcome.
   */
  public CompletedPayment initiatePayment(UUID userId, UUID bookingId, String idemKey) {
    if (idemKey == null || idemKey.isBlank()) {
      throw new IllegalArgumentException("Idempotency-Key header is required");
    }
    String key = idemKey.trim();
    if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
      throw new IllegalArgumentException(
          "Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters");
    }
    String fingerprint = fingerprint(bookingId);
    BeginOutcome begin = beginInFreshTransaction(userId, bookingId, key, fingerprint);
    return switch (begin) {
      case BeginOutcome.Replay(var response, var status) -> new CompletedPayment(response, status);
      case BeginOutcome.Lead(var payment, var keyId) -> {
        ProviderCall call = invokeProvider(payment);
        yield tx.execute(status -> finishAttempt(payment.id(), keyId, call));
      }
      case BeginOutcome.Reclaim(var paymentId, var keyId) -> reclaim(paymentId, keyId);
    };
  }

  @Transactional(readOnly = true)
  public PaymentResponse getPayment(UUID userId, UUID paymentId) {
    Payment payment =
        payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment not found"));
    if (!payment.getUserId().equals(userId)) {
      throw new NotFoundException("Payment not found");
    }
    return responseOf(payment);
  }

  /**
   * Internal orchestration step (also used by tests to simulate crash recovery).
   * Records the attempt and transitions the payment; on success confirms the booking
   * atomically in the same transaction. Idempotent: finishing an already-terminal
   * payment returns its current state without changing anything.
   */
  @Transactional
  public CompletedPayment finishAttempt(UUID paymentId, UUID keyId, ProviderCall call) {
    Payment payment =
        payments.findByIdForUpdate(paymentId).orElseThrow(() -> new NotFoundException("Payment not found"));
    IdempotencyKey key =
        keys.findById(keyId).orElseThrow(() -> new NotFoundException("Idempotency key not found"));
    if (payment.isTerminal()) {
      return new CompletedPayment(responseOf(payment), 200);
    }
    attempts.save(new PaymentAttempt(payment, call.outcome(), call.errorCode(), call.latencyMs()));
    return switch (call.outcome()) {
      case SUCCESS -> {
        payment.succeed();
        yield confirmOrRecordConflict(payment, key);
      }
      case DECLINED -> {
        payment.fail(call.errorCode());
        yield complete(key, 201, responseOf(payment));
      }
      case TIMEOUT, ERROR -> {
        // Ambiguity is never translated into failure.
        payment.markUnknown();
        yield complete(key, 202, responseOf(payment));
      }
    };
  }

  /**
   * Deletes only keys past their own expiry whose payment reached a terminal state
   * (SUCCEEDED, FAILED, or REFUNDED - a refunded payment is definitively done) or
   * orphan keys. Retaining terminal-payment keys until expiry preserves idempotent
   * replay for the documented key TTL. UNKNOWN-payment keys are never deleted, and
   * refund history rows live in a separate table this cleanup never touches.
   */
  @Transactional
  public int purgeExpiredKeys() {
    return keys.deleteExpiredTerminal(
        List.of(PaymentStatus.SUCCEEDED, PaymentStatus.FAILED, PaymentStatus.REFUNDED),
        clock.instant());
  }

  // ---- Tx1: durable intent ----

  private sealed interface BeginOutcome permits BeginOutcome.Replay, BeginOutcome.Lead, BeginOutcome.Reclaim {
    record Replay(PaymentResponse response, int httpStatus) implements BeginOutcome {}

    record Lead(PaymentSnapshot payment, UUID keyId) implements BeginOutcome {}

    record Reclaim(UUID paymentId, UUID keyId) implements BeginOutcome {}
  }

  private record PaymentSnapshot(
      UUID id, UUID bookingId, String providerRef, long amountMinor, String currency) {}

  @Transactional
  private BeginOutcome beginAttempt(UUID userId, UUID bookingId, String idemKey, String fingerprint) {
    KeyAcquisition acquired = acquireKey(userId, idemKey, fingerprint);
    IdempotencyKey keyRow = acquired.row();
    // Bind every replay/reclaim branch to the original request. Without this,
    // a stale IN_PROGRESS key could act on a different booking after a crash.
    if (!keyRow.getRequestHash().equals(fingerprint)) {
      throw new UnprocessableException("Idempotency key already used for a different request");
    }
    if (keyRow.getStatus() == KeyStatus.COMPLETED) {
      if (!isStale(keyRow)) {
        return new BeginOutcome.Replay(readBody(keyRow), keyRow.getResponseCode());
      }
      Payment prior =
          keyRow.getPaymentId() == null
              ? null
              : payments.findById(keyRow.getPaymentId()).orElse(null);
      if (prior == null) {
        return new BeginOutcome.Replay(readBody(keyRow), keyRow.getResponseCode());
      }
      if (prior.isTerminal()) {
        // The operation resolved after this key stored a 202/UNKNOWN response.
        // Return current state instead of a permanently stale replay.
        PaymentResponse body = responseOf(prior);
        keyRow.complete(200, writeBody(body));
        return new BeginOutcome.Replay(body, 200);
      }
      if (isExpired(keyRow)) {
        // A stale key over an unresolved payment must never replay or authorize a charge.
        throw new ConflictException("Payment already in progress");
      }
      return new BeginOutcome.Reclaim(prior.getId(), keyRow.getId());
    }
    if (acquired.createdByMe()) {
      // This request won the insert: proceed as leader.
      return leadFrom(userId, bookingId, keyRow);
    }
    if (!isStale(keyRow)) {
      // Another request created this key moments ago and owns the operation.
      throw new ConflictException("Payment already in progress");
    }
    if (keyRow.getPaymentId() == null) {
      return leadFrom(userId, bookingId, keyRow);
    }
    Payment prior = payments.findById(keyRow.getPaymentId()).orElse(null);
    if (prior != null && prior.isTerminal()) {
      // Stale key over a terminal payment: complete from stored state, never re-charge.
      PaymentResponse body = responseOf(prior);
      keyRow.complete(200, writeBody(body));
      return new BeginOutcome.Replay(body, 200);
    }
    if (prior != null && isExpired(keyRow)) {
      // An expired key over an unresolved payment must never authorize a new charge.
      throw new ConflictException("Payment already in progress");
    }
    if (prior == null) {
      // Defensive: linked payment vanished; fall back to a fresh leader attempt.
      return leadFrom(userId, bookingId, keyRow);
    }
    return new BeginOutcome.Reclaim(prior.getId(), keyRow.getId());
  }

  private record KeyAcquisition(IdempotencyKey row, boolean createdByMe) {}

  private KeyAcquisition acquireKey(UUID userId, String idemKey, String fingerprint) {
    IdempotencyKey existing = keys.findByUserIdAndKey(userId, idemKey).orElse(null);
    if (existing != null) {
      return new KeyAcquisition(existing, false);
    }
    try {
      IdempotencyKey created =
          new IdempotencyKey(userId, idemKey, fingerprint, clock.instant().plusSeconds(keyTtlHours * 3600));
      return new KeyAcquisition(keys.saveAndFlush(created), true);
    } catch (DataIntegrityViolationException duplicate) {
      // A concurrent insert won. PostgreSQL aborts the whole transaction on any
      // error, so nothing may continue here — signal a retry in a fresh transaction.
      throw new KeyWriteConflict();
    }
  }

  private BeginOutcome leadFrom(UUID userId, UUID bookingId, IdempotencyKey keyRow) {
    Booking booking =
        bookings
            .findByIdForUpdate(bookingId)
            .orElseThrow(() -> new NotFoundException("Booking not found"));
    if (!booking.getUserId().equals(userId)) {
      throw new NotFoundException("Booking not found");
    }
    if (booking.getStatus() != BookingStatus.PENDING_PAYMENT) {
      throw new ConflictException("Booking is not payable");
    }
    if (payments.findByBookingId(bookingId).isPresent()) {
      throw new ConflictException("Payment already exists for booking");
    }
    Payment payment = new Payment(bookingId, userId, booking.getTotalMinor(), booking.getCurrency());
    payment.authorize();
    payments.save(payment);
    keyRow.linkPayment(payment.getId());
    return new BeginOutcome.Lead(
        new PaymentSnapshot(
            payment.getId(), bookingId, payment.getProviderRef(), payment.getAmountMinor(), payment.getCurrency()),
        keyRow.getId());
  }

  // ---- provider invocation (never inside a transaction) ----

  private ProviderCall invokeProvider(PaymentSnapshot payment) {
    long start = clock.millis();
    Future<PaymentProvider.ChargeResult> future =
        providerCalls.submit(
            () -> provider.charge(payment.providerRef(), payment.amountMinor(), payment.currency()));
    try {
      PaymentProvider.ChargeResult result = future.get(providerTimeoutMs, TimeUnit.MILLISECONDS);
      long latency = clock.millis() - start;
      if (result.success()) {
        return new ProviderCall(AttemptOutcome.SUCCESS, latency, null);
      }
      return new ProviderCall(AttemptOutcome.DECLINED, latency, result.declineCode());
    } catch (TimeoutException timeout) {
      future.cancel(true);
      return new ProviderCall(AttemptOutcome.TIMEOUT, providerTimeoutMs, "TIMEOUT");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      return new ProviderCall(AttemptOutcome.ERROR, clock.millis() - start, "INTERRUPTED");
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      return new ProviderCall(AttemptOutcome.ERROR, clock.millis() - start, cause.getClass().getSimpleName());
    }
  }

  /**
   * Lookups are external calls too. A provider that hangs must not pin an HTTP
   * request or the shared scheduler thread; timeout is treated as indeterminate.
   */
  private Optional<PaymentProvider.ChargeResult> lookupWithTimeout(String providerRef) {
    Future<Optional<PaymentProvider.ChargeResult>> future =
        providerCalls.submit(() -> provider.lookup(providerRef));
    try {
      return future.get(providerTimeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException timeout) {
      future.cancel(true);
      throw new ProviderStatusUnknownException("Provider lookup timed out");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new ProviderStatusUnknownException("Provider lookup interrupted");
    } catch (ExecutionException failure) {
      Throwable cause = failure.getCause() == null ? failure : failure.getCause();
      if (cause instanceof ProviderStatusUnknownException ambiguous) {
        throw ambiguous;
      }
      throw new ProviderStatusUnknownException("Provider lookup failed: " + cause.getClass().getSimpleName());
    }
  }

  // ---- recovery (no transaction for the lookup itself) ----

  private CompletedPayment reclaim(UUID paymentId, UUID keyId) {
    Payment payment = payments.findById(paymentId).orElseThrow(() -> new NotFoundException("Payment not found"));
    if (payment.isTerminal()) {
      return tx.execute(status -> completeKeyTx(keyId, 200, responseOf(payment)));
    }
    Optional<PaymentProvider.ChargeResult> known;
    try {
      known = lookupWithTimeout(payment.getProviderRef());
    } catch (ProviderStatusUnknownException ambiguous) {
      return tx.execute(
          status -> finishAttempt(paymentId, keyId, new ProviderCall(AttemptOutcome.ERROR, 0, "AMBIGUOUS_LOOKUP")));
    }
    if (known.isEmpty()) {
      // Provider definitively has no record: re-driving the same stable reference
      // is safe, and provider-side deduplication covers a racing duplicate.
      ProviderCall call =
          invokeProvider(
              new PaymentSnapshot(
                  payment.getId(),
                  payment.getBookingId(),
                  payment.getProviderRef(),
                  payment.getAmountMinor(),
                  payment.getCurrency()));
      return tx.execute(txStatus -> finishAttempt(paymentId, keyId, call));
    }
    PaymentProvider.ChargeResult recorded = known.get();
    ProviderCall recovered =
        recorded.success()
            ? new ProviderCall(AttemptOutcome.SUCCESS, 0, "RECOVERED_VIA_LOOKUP")
            : new ProviderCall(AttemptOutcome.DECLINED, 0, recorded.declineCode());
    return tx.execute(txStatus -> finishAttempt(paymentId, keyId, recovered));
  }

  // ---- shared transactional helpers ----

  private CompletedPayment confirmOrRecordConflict(Payment payment, IdempotencyKey key) {
    Booking booking =
        bookings
            .findByIdForUpdate(payment.getBookingId())
            .orElseThrow(() -> new NotFoundException("Booking not found"));
    if (booking.getStatus() == BookingStatus.PENDING_PAYMENT) {
      BookingResponse confirmed = bookingService.confirmBooking(booking.getId());
      if ("CONFIRMED".equals(confirmed.status())) {
        return complete(key, 201, responseOf(payment));
      }
    }
    // Truthful record, not a mislabeled failure: money was captured but the booking
    // already expired. Seats stay released; the payment is flagged for Admin refund.
    payment.annotate("BOOKING_EXPIRED_AT_CONFIRM");
    return complete(key, 409, responseOf(payment));
  }

  @Transactional
  private CompletedPayment completeKeyTx(UUID keyId, int httpStatus, PaymentResponse body) {
    IdempotencyKey key =
        keys.findById(keyId).orElseThrow(() -> new NotFoundException("Idempotency key not found"));
    return complete(key, httpStatus, body);
  }

  private CompletedPayment complete(IdempotencyKey key, int httpStatus, PaymentResponse body) {
    key.complete(httpStatus, writeBody(body));
    return new CompletedPayment(body, httpStatus);
  }

  private boolean isStale(IdempotencyKey key) {
    return !key.getCreatedAt().plusSeconds(reclaimAfterSeconds).isAfter(clock.instant());
  }

  private boolean isExpired(IdempotencyKey key) {
    return !key.getExpiresAt().isAfter(clock.instant());
  }

  private PaymentResponse responseOf(Payment payment) {
    return new PaymentResponse(
        payment.getId(),
        payment.getBookingId(),
        payment.getStatus().name(),
        payment.getAmountMinor(),
        payment.getCurrency(),
        payment.getProviderRef(),
        payment.getNote());
  }

  private String writeBody(PaymentResponse body) {
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot serialize payment response", e);
    }
  }

  private PaymentResponse readBody(IdempotencyKey key) {
    try {
      return objectMapper.readValue(key.getResponseBody(), PaymentResponse.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot replay stored payment response", e);
    }
  }

  private static String fingerprint(UUID bookingId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash =
          digest.digest(
              ("POST|/api/v1/payments|" + bookingId).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
