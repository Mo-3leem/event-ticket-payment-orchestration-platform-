package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.ticketpay.payments.AttemptOutcome;
import com.ticketpay.payments.IdempotencyKey;
import com.ticketpay.payments.IdempotencyKeyRepository;
import com.ticketpay.payments.MockPaymentProvider;
import com.ticketpay.payments.Payment;
import com.ticketpay.payments.PaymentProvider;
import com.ticketpay.payments.PaymentRepository;
import com.ticketpay.payments.PaymentService;
import com.ticketpay.payments.ReconciliationRecordRepository;
import com.ticketpay.payments.ReconciliationService;
import com.ticketpay.payments.dto.ReviewNoteRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestHoldTimes.class)
class ReconciliationTest extends BaseTest {

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
  @Autowired ReconciliationRecordRepository records;
  @Autowired ReconciliationService reconciliation;
  @Autowired MockPaymentProvider mock;
  @Autowired TestHoldTimes holdTimes;
  @PersistenceContext EntityManager em;

  @BeforeEach
  void resetMock() {
    mock.reset();
  }

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
                    .content(objectMapper.writeValueAsString(new RegisterRequest(email, "password123"))))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readTree(response).get("token").asText();
  }

  private UUID userIdOf(String email) {
    return users.findByEmail(email).orElseThrow().getId();
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

  /** Committed UNKNOWN payment with aged timestamps, simulating a stuck operation. */
  private Payment seedAgedUnknown(UUID userId, UUID bookingId) {
    Payment payment = new Payment(bookingId, userId, 2500, "EGP");
    payment.authorize();
    payment.markUnknown();
    paymentRepo.saveAndFlush(payment);
    holdTimes.backdatePaymentCreated(payment.getId(), Instant.now().minusSeconds(11 * 60));
    return paymentRepo.findById(payment.getId()).orElseThrow();
  }

  // ---- transitions ----

  @Test
  @Transactional
  void unknown_resolves_to_success_with_confirmation() {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());

    assertThat(reconciliation.reconcileBatch()).isGreaterThanOrEqualTo(1);

    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name())
        .isEqualTo("SUCCEEDED");
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(records.countByPaymentId(payment.getId())).isEqualTo(1);
    assertThat(records.findAll().stream()
            .filter(r -> r.getPayment().getId().equals(payment.getId()))
            .findFirst()
            .orElseThrow()
            .getVerdict()
            .name())
        .isEqualTo("PROVIDER_SUCCESS");
  }

  @Test
  @Transactional
  void unknown_success_after_expiry_never_confirms() {
    User user = newUser("payer", Role.USER);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());

    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);
    assertThat(reconciliation.reconcileBatch()).isGreaterThanOrEqualTo(1);

    Payment stored = paymentRepo.findById(payment.getId()).orElseThrow();
    assertThat(stored.getStatus().name()).isEqualTo("SUCCEEDED");
    assertThat(stored.getNote()).isNotBlank();
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
  }

  @Test
  @Transactional
  void unknown_resolves_to_failed_on_definitive_decline() {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.declined("INSUFFICIENT_FUNDS"));

    assertThat(reconciliation.reconcileBatch()).isGreaterThanOrEqualTo(1);

    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("PENDING_PAYMENT");
    assertThat(records.countByPaymentId(payment.getId())).isEqualTo(1);
  }

  @Test
  @Transactional
  void empty_lookup_preserves_unknown_and_updates_timing() {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);

    assertThat(reconciliation.reconcileBatch()).isGreaterThanOrEqualTo(0);
    Payment stored = paymentRepo.findById(payment.getId()).orElseThrow();
    assertThat(stored.getStatus().name()).isEqualTo("UNKNOWN");
    assertThat(stored.getLastReconciledAt()).isNotNull();
    assertThat(records.countByPaymentId(payment.getId())).isZero();

    // Second cycle skips it (recently checked) without new records.
    assertThat(reconciliation.reconcileBatch()).isZero();
  }

  @Test
  @Transactional
  void ambiguous_lookup_preserves_unknown_and_updates_timing() {
    mock.setLookupThrows(true);
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);

    reconciliation.reconcileBatch();

    Payment stored = paymentRepo.findById(payment.getId()).orElseThrow();
    assertThat(stored.getStatus().name()).isEqualTo("UNKNOWN");
    assertThat(stored.getLastReconciledAt()).isNotNull();
    assertThat(records.countByPaymentId(payment.getId())).isZero();
    assertThat(mock.getProcessedCount()).isEqualTo(0);
  }

  @Test
  @Transactional
  void repeated_reconciliation_does_not_duplicate_records() {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());

    assertThat(reconciliation.reconcileBatch()).isGreaterThanOrEqualTo(1);
    assertThat(reconciliation.reconcileBatch()).isZero();
    assertThat(records.countByPaymentId(payment.getId())).isEqualTo(1);
  }

  // ---- races ----

  @Test
  void reconcile_racing_expiry_leaves_consistent_state() throws Exception {
    User user = newUser("payer", Role.USER);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Future<?> expiry =
        pool.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
              }
              bookings.expireBookings();
            });
    Future<Integer> recon =
        pool.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
              }
              return reconciliation.reconcileBatch();
            });
    start.countDown();
    expiry.get(30, TimeUnit.SECONDS);
    recon.get(30, TimeUnit.SECONDS);
    pool.shutdown();
    // Drain any leftover UNKNOWN (expiry may have won the race).
    reconciliation.reconcileBatch();

    String bookingStatus = bookingRepo.findById(bookingId).orElseThrow().getStatus().name();
    String paymentStatus = paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name();
    String seatStatus = eventSeats.findById(seat.getId()).orElseThrow().getStatus().name();
    if (bookingStatus.equals("CONFIRMED")) {
      assertThat(paymentStatus).isEqualTo("SUCCEEDED");
      assertThat(seatStatus).isEqualTo(EventSeatStatus.SOLD.name());
    } else {
      assertThat(bookingStatus).isEqualTo("EXPIRED");
      assertThat(seatStatus).isEqualTo(EventSeatStatus.AVAILABLE.name());
      assertThat(paymentStatus).isIn("UNKNOWN", "SUCCEEDED");
    }
    assertThat(records.countByPaymentId(payment.getId())).isLessThanOrEqualTo(1);

    // Test-teardown: free the seat regardless of which side won, so the shared
    // 20-seat seed stays reusable across unlimited suite runs.
    holdTimes.releaseCommittedBooking(bookingId);
  }

  @Test
  void reconcile_racing_live_completion_resolves_once() throws Exception {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());
    // Stale IN_PROGRESS key pointing at the unresolved payment (crash shape): a live
    // recovery through the public entry point races the scheduled reconciler here.
    // Either path may win; both converge on SUCCEEDED + CONFIRMED exactly once.
    String raceKey = "k-race-" + UUID.randomUUID();
    IdempotencyKey staleKey =
        keyRepo.saveAndFlush(
            new IdempotencyKey(
                user.getId(), raceKey, "stale-fingerprint", Instant.now().plusSeconds(86400)));
    staleKey.linkPayment(payment.getId());
    keyRepo.saveAndFlush(staleKey);
    // No EntityManager flush needed here: this test runs without a surrounding
    // transaction, so every service and JDBC call above already committed.
    holdTimes.backdateKeyCreated(staleKey.getId(), Instant.now().minusSeconds(300));

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Throwable> failures = new ArrayList<>();
    Future<?> liveRecovery =
        pool.submit(
            () -> {
              try {
                start.await();
                paymentService.initiatePayment(user.getId(), bookingId, raceKey);
              } catch (Exception e) {
                synchronized (failures) {
                  failures.add(e);
                }
              }
            });
    Future<Integer> recon =
        pool.submit(
            () -> {
              try {
                start.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 0;
              }
              return reconciliation.reconcileBatch();
            });
    start.countDown();
    liveRecovery.get(30, TimeUnit.SECONDS);
    recon.get(30, TimeUnit.SECONDS);
    pool.shutdown();

    assertThat(failures).isEmpty();
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name())
        .isEqualTo("SUCCEEDED");
    assertThat(records.countByPaymentId(payment.getId())).isLessThanOrEqualTo(1);

    // Test-teardown: the booking is CONFIRMED (seat SOLD); remove the committed
    // footprint so the shared seed stays reusable.
    holdTimes.releaseCommittedBooking(bookingId);
  }

  @Test
  void concurrent_workers_resolve_once() throws Exception {
    User user = newUser("payer", Role.USER);
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAgedUnknown(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          pool.submit(
              () -> {
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return 0;
                }
                return reconciliation.reconcileBatch();
              }));
    }
    start.countDown();
    int total = 0;
    for (Future<Integer> f : futures) {
      total += f.get(30, TimeUnit.SECONDS);
    }
    pool.shutdown();

    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name())
        .isEqualTo("SUCCEEDED");
    assertThat(records.countByPaymentId(payment.getId())).isEqualTo(1);
    assertThat(total).isGreaterThanOrEqualTo(1);

    // Test-teardown: free the confirmed seat for future runs.
    holdTimes.releaseCommittedBooking(bookingId);
  }

  // ---- history, admin, review ----

  @Test
  @Transactional
  void owner_history_is_scoped_and_filterable() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    String tokenB = tokenFor(emailB);
    UUID userIdA = userIdOf(emailA);
    paymentService.initiatePayment(userIdA, bookOneSeat(userIdA), "hk-1-" + UUID.randomUUID());
    paymentService.initiatePayment(userIdA, bookOneSeat(userIdA), "hk-2-" + UUID.randomUUID());

    mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            get("/api/v1/payments").header("Authorization", "Bearer " + tokenA).param("status", "SUCCEEDED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2));
    mvc.perform(
            get("/api/v1/payments").header("Authorization", "Bearer " + tokenA).param("status", "FAILED"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/v1/payments").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(
            get("/api/v1/payments").header("Authorization", "Bearer " + tokenA).param("status", "BOGUS"))
        .andExpect(status().isBadRequest());
  }

  @Test
  @Transactional
  void admin_reads_require_admin_role() throws Exception {
    String userEmail = "user-" + UUID.randomUUID() + "@example.com";
    String userToken = tokenFor(userEmail);
    User admin = newUser("admin", Role.ADMIN);
    String adminToken = jwt.generateToken(admin);
    UUID bookingId = bookOneSeat(userIdOf(userEmail));
    paymentService.initiatePayment(userIdOf(userEmail), bookingId, "ak-" + UUID.randomUUID());
    UUID paymentId = paymentRepo.findByBookingId(bookingId).orElseThrow().getId();

    mvc.perform(get("/api/v1/admin/payments").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").isArray());
    mvc.perform(
            get("/api/v1/admin/payments")
                .header("Authorization", "Bearer " + adminToken)
                .param("status", "SUCCEEDED"))
        .andExpect(status().isOk());
    mvc.perform(
            get("/api/v1/admin/reconciliation").header("Authorization", "Bearer " + adminToken))
        .andExpect(status().isOk());

    mvc.perform(get("/api/v1/admin/payments").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/v1/admin/reconciliation").header("Authorization", "Bearer " + userToken))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/admin/reconciliation/" + paymentId + "/review")
                .header("Authorization", "Bearer " + userToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReviewNoteRequest("ok"))))
        .andExpect(status().isForbidden());
  }

  @Test
  @Transactional
  void manual_review_appends_note_without_moving_money() throws Exception {
    User admin = newUser("admin", Role.ADMIN);
    String adminToken = jwt.generateToken(admin);
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);
    paymentService.initiatePayment(userId, bookingId, "rk-" + UUID.randomUUID());
    UUID paymentId = paymentRepo.findByBookingId(bookingId).orElseThrow().getId();

    mvc.perform(
            post("/api/v1/admin/reconciliation/" + paymentId + "/review")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReviewNoteRequest("operator verified with bank"))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.verdict").value("MANUAL_REVIEW"));

    // Nothing moved: payment, booking, and seats untouched; exactly one record.
    assertThat(paymentRepo.findById(paymentId).orElseThrow().getStatus().name())
        .isEqualTo("SUCCEEDED");
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(records.countByPaymentId(paymentId)).isEqualTo(1);

    mvc.perform(
            post("/api/v1/admin/reconciliation/" + UUID.randomUUID() + "/review")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReviewNoteRequest("nope"))))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/v1/admin/reconciliation/" + paymentId + "/review")
                .header("Authorization", "Bearer " + adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new ReviewNoteRequest(""))))
        .andExpect(status().isBadRequest());
  }

  
}
