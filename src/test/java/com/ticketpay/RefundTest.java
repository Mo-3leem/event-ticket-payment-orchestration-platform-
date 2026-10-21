package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.booking.BookingRepository;
import com.ticketpay.booking.BookingService;
import com.ticketpay.booking.HoldService;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.identity.JwtService;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import com.ticketpay.identity.dto.RegisterRequest;
import com.ticketpay.payments.IdempotencyKeyRepository;
import com.ticketpay.payments.MockPaymentProvider;
import com.ticketpay.payments.Payment;
import com.ticketpay.payments.PaymentProvider;
import com.ticketpay.payments.PaymentRepository;
import com.ticketpay.payments.PaymentService;
import com.ticketpay.payments.PaymentStatus;
import com.ticketpay.payments.ReconciliationRecordRepository;
import com.ticketpay.payments.ReconciliationService;
import com.ticketpay.payments.Refund;
import com.ticketpay.payments.RefundRepository;
import com.ticketpay.payments.RefundService;
import com.ticketpay.payments.dto.RefundResponse;
import com.ticketpay.payments.RefundStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestHoldTimes.class)
class RefundTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired JwtService jwt;
  @Autowired EventSeatRepository eventSeats;
  @Autowired HoldService holds;
  @Autowired BookingService bookings;
  @Autowired BookingRepository bookingRepo;
  @Autowired PaymentService paymentService;
  @Autowired PaymentRepository paymentRepo;
  @Autowired IdempotencyKeyRepository keyRepo;
  @Autowired RefundRepository refundRepo;
  @Autowired RefundService refundService;
  @Autowired ReconciliationRecordRepository records;
  @Autowired ReconciliationService reconciliation;
  @Autowired MockPaymentProvider mock;
  @Autowired TestHoldTimes holdTimes;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager em;

  @BeforeEach
  void resetMock() {
    mock.reset();
  }

  // ---- fixtures ----

  private User newUser(String prefix, Role role) {
    return users.save(
        new User(
            prefix + "-" + UUID.randomUUID() + "@example.com",
            passwords.encode("password123"),
            role));
  }

  private String tokenFor(String email) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("token").asText();
  }

  private UUID userIdOf(String email) {
    return users.findByEmail(email).orElseThrow().getId();
  }

  private String adminToken() {
    return jwt.generateToken(newUser("admin", Role.ADMIN));
  }

  private EventSeat firstAvailableSeat() {
    return eventSeats.findAll().stream()
        .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No AVAILABLE seat in seed"));
  }

  private UUID bookOneSeat(UUID userId) {
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);
    return bookings.createBooking(userId, List.of(seat.getId())).bookingId();
  }

  /** Captured payment through the real initiation flow (mock succeeds). */
  private Payment seedCapturedPayment(UUID userId, String idemKey) {
    UUID bookingId = bookOneSeat(userId);
    paymentService.initiatePayment(userId, bookingId, idemKey);
    return paymentRepo.findByBookingId(bookingId).orElseThrow();
  }

  /** Payment in any state, written directly (like ReconciliationTest's seed). */
  private Payment seedPaymentInState(UUID userId, PaymentStatus state) {
    UUID bookingId = bookOneSeat(userId);
    Payment payment = new Payment(bookingId, userId, 2500, "EGP");
    switch (state) {
      case CREATED -> { /* leave as-is */ }
      case AUTHORIZING -> payment.authorize();
      case SUCCEEDED -> {
        payment.authorize();
        payment.succeed();
      }
      case FAILED -> {
        payment.authorize();
        payment.fail("seed");
      }
      case UNKNOWN -> {
        payment.authorize();
        payment.markUnknown();
      }
      case REFUNDED -> {
        payment.authorize();
        payment.succeed();
        payment.markRefunded();
      }
    }
    paymentRepo.saveAndFlush(payment);
    return paymentRepo.findById(payment.getId()).orElseThrow();
  }

  /**
   * Refund-eligible fixture: charge resolves SUCCESS after the booking already
   * expired — exactly PROVIDER_SUCCESS_BOOKING_EXPIRED. Payment SUCCEEDED with
   * the annotation note, booking EXPIRED, seat AVAILABLE.
   */
  private Payment seedExpiredCapture(UUID userId, String idemKey) {
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);
    UUID bookingId = bookings.createBooking(userId, List.of(seat.getId())).bookingId();
    Payment payment = new Payment(bookingId, userId, 2500, "EGP");
    payment.authorize();
    payment.markUnknown();
    paymentRepo.saveAndFlush(payment);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    bookings.expireBookings();
    reconciliation.reconcileBatch();
    Payment stored = paymentRepo.findById(payment.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(stored.getNote()).isEqualTo("BOOKING_EXPIRED_AT_CONFIRM");
    holdTimes.backdatePaymentCreated(payment.getId(), Instant.now().minusSeconds(3600));
    return stored;
  }

  private Refund seedRefundRow(Payment payment, UUID adminId, String note) {
    Refund refund = new Refund(payment, adminId, note);
    refundRepo.saveAndFlush(refund);
    return refundRepo.findById(refund.getId()).orElseThrow();
  }

  private long refundCompletedCount(UUID paymentId) {
    return records.countByPaymentId(paymentId);
  }

  /** State-based wait (condition polling), not blind timing: fails loudly on timeout. */
  private void awaitTrue(String what, BooleanSupplier condition) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out waiting for " + what);
      }
      Thread.sleep(5);
    }
  }

  // ---- 1. full refund success after booking expiry ----

  @Test
  @Transactional
  void full_refund_success_after_expiry_marks_payment_refunded() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    Payment payment = seedExpiredCapture(userId, "k-" + UUID.randomUUID());
    String admin = adminToken();

    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"expired capture refund\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.amountMinor").value(2500))
        .andExpect(jsonPath("$.currency").value("EGP"));

    Payment stored = paymentRepo.findById(payment.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    assertThat(refundRepo.findByPaymentId(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.SUCCEEDED);
    // Booking stays EXPIRED and its seats stay AVAILABLE — refund touches neither.
    assertThat(bookingRepo.findById(payment.getBookingId()).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    List<String> seatStatuses =
        jdbc.queryForList(
            "SELECT es.status FROM event_seats es"
                + " JOIN booking_items bi ON bi.event_seat_id = es.id"
                + " WHERE bi.booking_id = ?",
            String.class,
            payment.getBookingId());
    assertThat(seatStatuses).isNotEmpty().containsOnly("AVAILABLE");
    // Expired-booking record from charge reconciliation + exactly one REFUND_COMPLETED.
    List<com.ticketpay.payments.ReconciliationRecord> forPayment =
        records.findAll().stream()
            .filter(r -> r.getPayment().getId().equals(payment.getId()))
            .toList();
    assertThat(forPayment)
        .extracting(r -> r.getVerdict().name())
        .containsExactlyInAnyOrder(
            "PROVIDER_SUCCESS_BOOKING_EXPIRED", "REFUND_COMPLETED");
    // Refunded payments disappear from the refund-required flag.
    em.flush();
    em.clear();
    assertThat(
            refundService
                .refundRequired(org.springframework.data.domain.PageRequest.of(0, 50))
                .getContent())
        .noneMatch(p -> p.paymentId().equals(payment.getId()));
  }

  // ---- 2. definitive decline ----

  @Test
  @Transactional
  void definitive_refund_decline_keeps_payment_succeeded() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.DECLINED);
    String admin = adminToken();

    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"try refund\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("FAILED"));

    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.SUCCEEDED);
    assertThat(refundRepo.findByPaymentId(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.FAILED);
    assertThat(refundCompletedCount(payment.getId())).isZero();
  }

  // ---- 3. ambiguous refund stays UNKNOWN, no blind re-drive ----

  @Test
  @Transactional
  void ambiguous_refund_stays_unknown_and_is_never_redriven() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(
        new User(
            email,
            passwords.encode("password123"),
            Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    mock.setSleepMs(700); // > 500ms test provider timeout
    mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SLEEP_NO_RECORD);
    UUID adminId = newUser("admin", Role.ADMIN).getId();

    RefundResponse refund = refundService.initiateRefund(adminId, payment.getId(), "ambiguous");
    assertThat(refund.status()).isEqualTo("UNKNOWN");
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.SUCCEEDED);

    // Flush pending state and detach, so the JDBC backdate below cannot be
    // overwritten by a subsequent auto-flush of the managed refund entity.
    em.flush();
    em.clear();
    // Aged + provider lookup still ambiguous: stays UNKNOWN, no re-drive call.
    holdTimes.backdateRefundUpdated(refund.id(), Instant.now().minusSeconds(11 * 60));
    mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SUCCESS);
    mock.setRefundLookupThrows(true);
    assertThat(refundService.reconcileRefundBatch()).isZero();

    Refund stored = refundRepo.findById(refund.id()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(RefundStatus.UNKNOWN);
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.SUCCEEDED);
    // The single timed-out attempt recorded nothing; no further refund() call happened.
    assertThat(mock.getRefundProcessedCount()).isZero();
    assertThat(mock.hasRefundRecord(refund.providerRefundRef())).isFalse();
  }

  // ---- 4. lookup resolves ambiguous outcome with the SAME reference ----

  @Test
  @Transactional
  void reconciler_resolves_unknown_refund_via_same_reference() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    mock.setSleepMs(700);
    mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SLEEP_NO_RECORD);
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    RefundResponse refund = refundService.initiateRefund(adminId, payment.getId(), "ambiguous");
    assertThat(refund.status()).isEqualTo("UNKNOWN");

    // Provider later holds the definitive outcome under the SAME refund reference.
    mock.rehearseRefund(refund.providerRefundRef(), PaymentProvider.ChargeResult.ok());
    em.flush();
    em.clear();
    holdTimes.backdateRefundUpdated(refund.id(), Instant.now().minusSeconds(11 * 60));
    assertThat(refundService.reconcileRefundBatch()).isGreaterThanOrEqualTo(1);

    assertThat(refundRepo.findById(refund.id()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.SUCCEEDED);
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);
    // Resolved purely by lookup: the timed-out attempt was the only refund() call.
    assertThat(mock.getRefundProcessedCount()).isZero();
  }

  // ---- 5. crash before provider invocation -> safe same-reference re-drive ----

  @Test
  @Transactional
  void crash_before_provider_call_recovers_via_safe_redrive() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    // Crash simulation: durable intent exists, provider never called.
    Refund refund = seedRefundRow(payment, adminId, "crashed before call");
    holdTimes.backdateRefundUpdated(refund.getId(), Instant.now().minusSeconds(11 * 60));

    assertThat(refundService.reconcileRefundBatch()).isGreaterThanOrEqualTo(1);

    Refund stored = refundRepo.findById(refund.getId()).orElseThrow();
    assertThat(stored.getStatus()).isEqualTo(RefundStatus.SUCCEEDED);
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);
    // Exactly one provider operation, executed under the SAME pre-persisted reference.
    assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
    assertThat(mock.hasRefundRecord(stored.getProviderRefundRef())).isTrue();
    assertThat(stored.getProviderRefundRef()).isEqualTo(refund.getId().toString());
  }

  // ---- 6. provider executed but DB finalization crashed ----

  @Test
  @Transactional
  void crash_after_provider_before_finalize_resolves_from_lookup() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    Refund refund = seedRefundRow(payment, adminId, "crashed before finalize");
    // Provider already recorded the refund; only our Tx2 never ran.
    mock.rehearseRefund(refund.getProviderRefundRef(), PaymentProvider.ChargeResult.ok());
    holdTimes.backdateRefundUpdated(refund.getId(), Instant.now().minusSeconds(11 * 60));

    assertThat(refundService.reconcileRefundBatch()).isGreaterThanOrEqualTo(1);

    assertThat(refundRepo.findById(refund.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.SUCCEEDED);
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);
    // Resolved by lookup — no new refund() operation was executed.
    assertThat(mock.getRefundProcessedCount()).isZero();
  }

  // ---- 7. sequential duplicate Admin submission ----

  @Test
  @Transactional
  void sequential_duplicate_submission_never_charges_twice() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    String admin = adminToken();

    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"first\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"second\"}"))
        .andExpect(status().isConflict());

    assertThat(refundRepo.findAll().stream().filter(r -> r.getPayment().getId().equals(payment.getId())))
        .hasSize(1);
    assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
  }

  // ---- 8. concurrent Admin submissions (committed rows) ----

  @Test
  void concurrent_admin_submissions_run_exactly_one_provider_operation() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    String admin = adminToken();
    int threads = 4;
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      List<Future<Integer>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return mvc.perform(
                          post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                              .header("Authorization", "Bearer " + admin)
                              .contentType(MediaType.APPLICATION_JSON)
                              .content("{\"note\":\"concurrent\"}"))
                      .andReturn()
                      .getResponse()
                      .getStatus();
                }));
      }
      start.countDown();
      List<Integer> statuses = new ArrayList<>();
      for (Future<Integer> result : results) {
        statuses.add(result.get(30, TimeUnit.SECONDS));
      }

      assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
      assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);
      assertThat(
              refundRepo.findAll().stream()
                  .filter(r -> r.getPayment().getId().equals(payment.getId())))
          .hasSize(1);
      assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
      assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.REFUNDED);
    } finally {
      pool.shutdownNow();
      holdTimes.releaseCommittedBooking(payment.getBookingId());
    }
  }

  // ---- 9. refund racing charge reconciliation ----

  @Test
  void refund_recovery_races_charge_reconciliation_without_interference() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    // A: unresolved CHARGE (charge reconciler's domain).
    Payment stuckCharge = seedPaymentInState(userId, PaymentStatus.UNKNOWN);
    holdTimes.backdatePaymentCreated(stuckCharge.getId(), Instant.now().minusSeconds(11 * 60));
    mock.rehearse(stuckCharge.getProviderRef(), PaymentProvider.ChargeResult.ok());
    // B: captured payment with in-flight refund (refund reconciler's domain).
    Payment captured = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    try {
      mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SLEEP_NO_RECORD);
      mock.setSleepMs(700);
      UUID adminId = newUser("admin", Role.ADMIN).getId();
      RefundResponse refund = refundService.initiateRefund(adminId, captured.getId(), "raced");
      assertThat(refund.status()).isEqualTo("UNKNOWN");
      holdTimes.backdateRefundUpdated(refund.id(), Instant.now().minusSeconds(11 * 60));
      mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SUCCESS);

      reconciliation.reconcileBatch();
      refundService.reconcileRefundBatch();

      // Each reconciler resolves only its own domain, exactly once.
      assertThat(paymentRepo.findById(stuckCharge.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.SUCCEEDED);
      assertThat(refundRepo.findById(refund.id()).orElseThrow().getStatus())
          .isEqualTo(RefundStatus.SUCCEEDED);
      assertThat(paymentRepo.findById(captured.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.REFUNDED);
    } finally {
      holdTimes.releaseCommittedBooking(captured.getBookingId());
      holdTimes.releaseCommittedBooking(stuckCharge.getBookingId());
    }
  }

  // ---- 10. non-SUCCEEDED payment rejection ----

  @Test
  @Transactional
  void only_captured_payments_can_be_refunded() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    String admin = adminToken();
    for (PaymentStatus state :
        List.of(
            PaymentStatus.CREATED,
            PaymentStatus.AUTHORIZING,
            PaymentStatus.FAILED,
            PaymentStatus.UNKNOWN,
            PaymentStatus.REFUNDED)) {
      Payment payment = seedPaymentInState(userId, state);
      mvc.perform(
              post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                  .header("Authorization", "Bearer " + admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"note\":\"should be rejected\"}"))
          .andExpect(status().isConflict());
      assertThat(refundRepo.findByPaymentId(payment.getId())).isEmpty();
    }
    assertThat(mock.getRefundProcessedCount()).isZero();
  }

  // ---- 11. amount/currency ledger must equal captured payment ----

  @Test
  @Transactional
  void refund_ledger_amount_is_copied_from_payment_and_mismatch_is_refused() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    UUID adminId = newUser("admin", Role.ADMIN).getId();

    // Service-level guarantee: the ledger copies the captured amount/currency.
    RefundResponse refund = refundService.initiateRefund(adminId, payment.getId(), "exact");
    assertThat(refund.amountMinor()).isEqualTo(payment.getAmountMinor());
    assertThat(refund.currency()).isEqualTo(payment.getCurrency());

    // Defensive guard: a corrupt mismatched row can never finalize a refund.
    // Pre-record the outcome so the guard fails BEFORE any provider operation.
    UUID adminId2 = newUser("admin2", Role.ADMIN).getId();
    Payment other = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    Refund mismatched = seedRefundRow(other, adminId2, "will be corrupted");
    mock.rehearseRefund(
        mismatched.getProviderRefundRef(), PaymentProvider.ChargeResult.ok());
    // Detach managed entities so the raw JDBC corruption below survives (it would
    // otherwise be silently overwritten by an auto-flush of the stale entity).
    em.flush();
    em.clear();
    jdbc.update(
        "UPDATE refunds SET amount_minor = ? WHERE id = ?",
        mismatched.getAmountMinor() + 1,
        mismatched.getId());
    holdTimes.backdateRefundUpdated(mismatched.getId(), Instant.now().minusSeconds(11 * 60));
    // Batch isolation: the guard still refuses the row, but no longer aborts the pass.
    assertThat(refundService.reconcileRefundBatch()).isZero();
    assertThat(refundRepo.findById(mismatched.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.REQUESTED);
    assertThat(paymentRepo.findById(other.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.SUCCEEDED);
    // Only the legitimate first refund ever reached the provider; the corrupted
    // row was refused by the guard (resolved via rehearsed lookup, no re-drive).
    assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
  }

  // ---- 11b. batch isolation: a poisoned refund must not stall others ----

  @Test
  void refund_batch_isolates_poisoned_row_and_resolves_healthy_one() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    Payment healthyPayment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    Payment poisonedPayment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    try {
      Refund healthy = seedRefundRow(healthyPayment, adminId, "healthy");
      Refund poisoned = seedRefundRow(poisonedPayment, adminId, "poisoned");
      jdbc.update(
          "UPDATE refunds SET amount_minor = amount_minor + 1 WHERE id = ?", poisoned.getId());
      Instant aged = Instant.now().minusSeconds(11 * 60);
      holdTimes.backdateRefundUpdated(healthy.getId(), aged);
      holdTimes.backdateRefundUpdated(poisoned.getId(), aged);

      int resolved = refundService.reconcileRefundBatch();

      // The healthy refund resolved; the corrupted one failed in isolation.
      assertThat(resolved).isGreaterThanOrEqualTo(1);
      assertThat(refundRepo.findById(healthy.getId()).orElseThrow().getStatus())
          .isEqualTo(RefundStatus.SUCCEEDED);
      assertThat(paymentRepo.findById(healthyPayment.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.REFUNDED);
      assertThat(refundRepo.findById(poisoned.getId()).orElseThrow().getStatus())
          .isEqualTo(RefundStatus.REQUESTED);
      assertThat(paymentRepo.findById(poisonedPayment.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.SUCCEEDED);
      assertThat(refundCompletedCount(poisonedPayment.getId())).isZero();
    } finally {
      holdTimes.releaseCommittedBooking(healthyPayment.getBookingId());
      holdTimes.releaseCommittedBooking(poisonedPayment.getBookingId());
    }
  }

  // ---- 12. non-admin access ----

  @Test
  void non_admin_cannot_use_refund_endpoints() throws Exception {
    String email = "user-" + UUID.randomUUID() + "@example.com";
    String userToken = tokenFor(email);
    UUID paymentId = UUID.randomUUID();

    mvc.perform(
            get("/api/v1/admin/payments/refund-required")
                .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/payments/" + paymentId + "/refund")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"nope\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/payments/" + paymentId + "/refunds")
                .header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
  }

  // ---- 13. validation + deterministic ordering ----

  @Test
  @Transactional
  void refund_request_validation_and_deterministic_ordering() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    String admin = adminToken();

    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"" + "x".repeat(201) + "\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/admin/payments/" + payment.getId() + "/refund")
                .header("Authorization", "Bearer " + admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest());
    // Validation rejected everything before any provider operation.
    assertThat(mock.getRefundProcessedCount()).isZero();

    // Deterministic refund-required ordering (createdAt desc, id desc).
    Payment newer = seedExpiredCapture(userId, "k-" + UUID.randomUUID());
    Payment older = seedExpiredCapture(userId, "k-" + UUID.randomUUID());
    holdTimes.backdatePaymentCreated(older.getId(), Instant.now().minusSeconds(7200));
    holdTimes.backdatePaymentCreated(newer.getId(), Instant.now().minusSeconds(3600));
    em.flush();
    em.clear();
    List<UUID> ids =
        refundService
            .refundRequired(org.springframework.data.domain.PageRequest.of(0, 50))
            .getContent()
            .stream()
            .map(com.ticketpay.payments.dto.PaymentResponse::paymentId)
            .toList();
    assertThat(ids).contains(newer.getId(), older.getId());
    assertThat(ids.indexOf(newer.getId())).isLessThan(ids.indexOf(older.getId()));
  }

  // ---- 14. V7 constraint accepts REFUNDED, rejects unknown values ----

  @Test
  void v7_status_constraint_extended_for_refunded() {
    // A row with REFUNDED must be accepted by the replaced CHECK.
    UUID userId = newUser("v7-user", Role.USER).getId();
    Payment payment = seedPaymentInState(userId, PaymentStatus.REFUNDED);
    UUID paymentId = payment.getId();
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId))
        .isEqualTo("REFUNDED");
    // An out-of-contract status must still be rejected by the database.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "UPDATE payments SET status = 'BOGUS' WHERE id = ?", paymentId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    // Refund verdict constraint includes REFUND_COMPLETED and nothing else.
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "INSERT INTO reconciliation_records "
                        + "(id, payment_id, before_status, after_status, provider_verdict, note, created_at) "
                        + "VALUES (?, ?, 'SUCCEEDED', 'REFUNDED', 'NOT_A_VERDICT', NULL, now())",
                    UUID.randomUUID(),
                    paymentId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  // ---- 15. idempotency GC with REFUNDED payments; refund rows never purged ----

  @Test
  void gc_purges_refunded_payment_keys_but_never_refund_history() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);
    paymentService.initiatePayment(userId, bookingId, "gc-" + UUID.randomUUID());
    Payment payment = paymentRepo.findByBookingId(bookingId).orElseThrow();
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    refundService.initiateRefund(adminId, payment.getId(), "gc refund");
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);

    // Age the key past TTL, then GC.
    UUID keyId =
        keyRepo.findAll().stream()
            .filter(k -> payment.getId().equals(k.getPaymentId()))
            .findFirst()
            .orElseThrow()
            .getId();
    holdTimes.backdateKeyExpiry(keyId, Instant.now().minusSeconds(25 * 3600));
    paymentService.purgeExpiredKeys();

    assertThat(keyRepo.findById(keyId)).isEmpty();
    // Refund history survives retention cleanup.
    assertThat(refundRepo.findAll().stream().filter(r -> r.getPayment().getId().equals(payment.getId()))).hasSize(1);

    holdTimes.releaseCommittedBooking(bookingId);
  }

  // ---- 16. repeated reconciliation never duplicates finalization/records ----

  @Test
  @Transactional
  void repeated_reconciliation_does_not_duplicate_refund_records() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    mock.setSleepMs(700);
    mock.setRefundBehavior(MockPaymentProvider.RefundBehavior.SLEEP_NO_RECORD);
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    RefundResponse refund = refundService.initiateRefund(adminId, payment.getId(), "ambiguous");
    assertThat(refund.status()).isEqualTo("UNKNOWN");

    mock.rehearseRefund(refund.providerRefundRef(), PaymentProvider.ChargeResult.ok());
    em.flush();
    em.clear();
    holdTimes.backdateRefundUpdated(refund.id(), Instant.now().minusSeconds(11 * 60));
    assertThat(refundService.reconcileRefundBatch()).isGreaterThanOrEqualTo(1);
    int firstPass = (int) refundCompletedCount(payment.getId());
    assertThat(firstPass).isEqualTo(1);

    // Aged again: still in-flight? No — SUCCEEDED is excluded from the selector.
    holdTimes.backdateRefundUpdated(refund.id(), Instant.now().minusSeconds(22 * 60));
    assertThat(refundService.reconcileRefundBatch()).isZero();
    assertThat(refundCompletedCount(payment.getId())).isEqualTo(firstPass);
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
        .isEqualTo(PaymentStatus.REFUNDED);
  }

  // ---- 17. empty lookup permits re-drive ONLY under documented contract ----

  @Test
  @Transactional
  void empty_lookup_redrives_with_same_reference_and_stops_after_resolution() {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    UUID adminId = newUser("admin", Role.ADMIN).getId();
    Refund refund = seedRefundRow(payment, adminId, "empty lookup redrive");
    holdTimes.backdateRefundUpdated(refund.getId(), Instant.now().minusSeconds(11 * 60));

    // Empty lookup (no provider record) -> documented-safe same-reference re-drive.
    assertThat(refundService.reconcileRefundBatch()).isGreaterThanOrEqualTo(1);
    assertThat(refundRepo.findById(refund.getId()).orElseThrow().getStatus())
        .isEqualTo(RefundStatus.SUCCEEDED);
    assertThat(mock.hasRefundRecord(refund.getProviderRefundRef())).isTrue();
    assertThat(mock.getRefundProcessedCount()).isEqualTo(1);

    // Terminal refunds are never selected again: no second call, no second record.
    holdTimes.backdateRefundUpdated(refund.getId(), Instant.now().minusSeconds(33 * 60));
    assertThat(refundService.reconcileRefundBatch()).isZero();
    assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
    assertThat(refundCompletedCount(payment.getId())).isEqualTo(1);
  }

  // ---- 18. audit M1: recovery worker races the LIVE Admin finalization ----

  @Test
  void recovery_races_live_finalization_exactly_once() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    users.save(new User(email, passwords.encode("password123"), Role.USER));
    UUID userId = userIdOf(email);
    Payment payment = seedCapturedPayment(userId, "k-" + UUID.randomUUID());
    UUID adminId = newUser("admin", Role.ADMIN).getId();

    // Deterministic gate: both racers park at the provider BEFORE any execution,
    // so the provider record is provably absent when recovery does its lookup.
    CountDownLatch gate = new CountDownLatch(1);
    mock.setRefundGate(gate);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<RefundResponse> live =
          pool.submit(
              () -> {
                start.await();
                return refundService.initiateRefund(adminId, payment.getId(), "live vs recovery");
              });
      start.countDown();

      // Tx1 committed and the live Admin call is parked pre-execution.
      awaitTrue("live refund parked at provider gate", () -> mock.getRefundParkedCount() == 1);
      Refund refund = refundRepo.findByPaymentId(payment.getId()).orElseThrow();
      holdTimes.backdateRefundUpdated(refund.getId(), Instant.now().minusSeconds(11 * 60));

      // Recovery worker: aged + empty lookup -> documented same-reference re-drive,
      // parking alongside the live call before either executes.
      Future<Integer> recovery =
          pool.submit(
              () -> {
                start.await();
                return refundService.reconcileRefundBatch();
              });
      awaitTrue("recovery re-drive parked at provider gate", () -> mock.getRefundParkedCount() == 2);

      // Release both at once: the per-reference monitor must admit exactly one
      // execution (dedupe), and the refund row lock must admit exactly one
      // finalization (terminal transition + REFUND_COMPLETED record).
      gate.countDown();
      RefundResponse liveOutcome = live.get(30, TimeUnit.SECONDS);
      int resolved = recovery.get(30, TimeUnit.SECONDS);

      // Live Admin path always ends SUCCEEDED: it either performed the single
      // transition or observed recovery's completed one (idempotent finalize).
      assertThat(liveOutcome.status()).isEqualTo("SUCCEEDED");
      // At most ONE of the two racers may report a state change.
      assertThat(resolved).isLessThanOrEqualTo(1);
      // Final states: one refund row, one terminal transition, payment refunded.
      assertThat(refundRepo.findByPaymentId(payment.getId()).orElseThrow().getStatus())
          .isEqualTo(RefundStatus.SUCCEEDED);
      assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus())
          .isEqualTo(PaymentStatus.REFUNDED);
      // Exactly one provider execution, under the SAME pre-persisted reference.
      assertThat(mock.getRefundProcessedCount()).isEqualTo(1);
      assertThat(mock.hasRefundRecord(refund.getProviderRefundRef())).isTrue();
      assertThat(refund.getProviderRefundRef()).isEqualTo(refund.getId().toString());
      // Exactly one refund row and exactly one REFUND_COMPLETED record.
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM refunds WHERE payment_id = ?", Long.class,
                  payment.getId()))
          .isEqualTo(1L);
      assertThat(refundCompletedCount(payment.getId())).isEqualTo(1);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM reconciliation_records "
                      + "WHERE payment_id = ? AND provider_verdict = 'REFUND_COMPLETED'",
                  Long.class,
                  payment.getId()))
          .isEqualTo(1L);
    } finally {
      gate.countDown(); // release any parked straggler on failure paths
      mock.setRefundGate(null);
      pool.shutdownNow();
      holdTimes.releaseCommittedBooking(payment.getBookingId());
    }
  }
}
