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
import com.ticketpay.booking.dto.CreateBookingRequest;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
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
import com.ticketpay.payments.RefundService;
import com.ticketpay.payments.dto.InitiatePaymentRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

@Import(TestHoldTimes.class)
class PaymentsTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired EventSeatRepository eventSeats;
  @Autowired HoldService holds;
  @Autowired BookingService bookings;
  @Autowired BookingRepository bookingRepo;
  @Autowired PaymentService paymentService;
  @Autowired PaymentRepository paymentRepo;
  @Autowired RefundService refundService;
  @Autowired IdempotencyKeyRepository keyRepo;
  @Autowired MockPaymentProvider mock;
  @Autowired TestHoldTimes holdTimes;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager em;

  @BeforeEach
  void resetMock() {
    mock.reset();
  }

  private User newUser(String prefix) {
    return users.save(
        new User(
            prefix + "-" + UUID.randomUUID() + "@example.com",
            passwords.encode("password123"),
            Role.USER));
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

  private String key() {
    return "k-" + UUID.randomUUID();
  }

  private static String fingerprintOf(UUID bookingId) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(("POST|/api/v1/payments|" + bookingId).getBytes(StandardCharsets.UTF_8));
      StringBuilder hex = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        hex.append(Character.forDigit((b >> 4) & 0xF, 16));
        hex.append(Character.forDigit(b & 0xF, 16));
      }
      return hex.toString();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /** Crash simulation: durable AUTHORIZING payment + IN_PROGRESS key, no provider call yet. */
  private Payment seedAuthorizing(UUID userId, UUID bookingId) {
    var booking = bookingRepo.findById(bookingId).orElseThrow();
    Payment payment = new Payment(bookingId, userId, booking.getTotalMinor(), booking.getCurrency());
    payment.authorize();
    return paymentRepo.saveAndFlush(payment);
  }

  private IdempotencyKey seedKey(UUID userId, String key, UUID bookingId, UUID paymentId) {
    IdempotencyKey row =
        new IdempotencyKey(userId, key, fingerprintOf(bookingId), Instant.now().plusSeconds(86400));
    if (paymentId != null) {
      row.linkPayment(paymentId);
    }
    return keyRepo.saveAndFlush(row);
  }

  // ---- happy path and explicit outcomes ----

  @Test
  @Transactional
  void success_confirms_booking_and_sells_seats_atomically() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);
    UUID seatId =
        eventSeats.findAll().stream()
            .filter(es -> es.getStatus() == EventSeatStatus.HELD)
            .filter(es -> userId.equals(es.getHeldByUserId()))
            .findFirst()
            .orElseThrow()
            .getId();

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"))
        .andExpect(jsonPath("$.bookingId").value(bookingId.toString()));

    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(eventSeats.findById(seatId).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.SOLD);
    assertThat(mock.getProcessedCount()).isEqualTo(1);
  }

  @Test
  @Transactional
  void decline_records_failed_and_leaves_booking_pending() throws Exception {
    mock.setBehavior(MockPaymentProvider.MockBehavior.DECLINED);
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("FAILED"));

    assertThat(paymentRepo.findByBookingId(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("PENDING_PAYMENT");
  }

  @Test
  @Transactional
  void timeout_records_unknown_without_claiming_failure() throws Exception {
    mock.setBehavior(MockPaymentProvider.MockBehavior.SLEEP_NO_RECORD);
    mock.setSleepMs(10_000);
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId))))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.status").value("UNKNOWN"));

    Payment payment = paymentRepo.findByBookingId(bookingId).orElseThrow();
    assertThat(payment.getStatus().name()).isEqualTo("UNKNOWN");
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("PENDING_PAYMENT");
    assertThat(mock.hasRecord(payment.getProviderRef())).isFalse();
  }

  // ---- idempotency ----

  @Test
  @Transactional
  void same_key_replay_does_not_charge_twice() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);
    String idemKey = key();
    String body =
        objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId));

    MvcResult first =
        mvc.perform(
                post("/api/v1/payments")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", idemKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isCreated())
            .andReturn();
    MvcResult second =
        mvc.perform(
                post("/api/v1/payments")
                    .header("Authorization", "Bearer " + token)
                    .header("Idempotency-Key", idemKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            // Replay returns the originally stored response verbatim.
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(second.getResponse().getContentAsString())
        .isEqualTo(first.getResponse().getContentAsString());
    assertThat(mock.getProcessedCount()).isEqualTo(1);
    // Exactly one payment row for this booking (relative: other tests commit their own).
    assertThat(paymentRepo.findByBookingId(bookingId)).isPresent();
  }

  @Test
  void concurrent_same_key_produces_single_operation() throws Exception {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    String idemKey = key();
    long paymentsBefore = paymentRepo.count();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Integer> statuses = new ArrayList<>();
    List<String> bodies = new ArrayList<>();
    List<Throwable> failures = new ArrayList<>();

    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
      futures.add(
          pool.submit(
              () -> {
                ready.countDown();
                try {
                  start.await();
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
                }
                try {
                  PaymentService.CompletedPayment result =
                      paymentService.initiatePayment(user.getId(), bookingId, idemKey);
                  synchronized (statuses) {
                    statuses.add(result.httpStatus());
                    if (result.httpStatus() == 201) {
                      bodies.add(objectMapper.writeValueAsString(result.body()));
                    }
                  }
                } catch (RuntimeException ex) {
                  // Fresh-duplicate racing the leader sees IN_PROGRESS and gets 409;
                  // anything else is a real defect.
                  if (ex instanceof com.ticketpay.shared.error.ConflictException) {
                    synchronized (statuses) {
                      statuses.add(409);
                    }
                  } else {
                    synchronized (failures) {
                      failures.add(ex);
                    }
                  }
                } catch (Exception ex) {
                  synchronized (failures) {
                    failures.add(ex);
                  }
                }
              }));
    }

    ready.await();
    start.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(failures).isEmpty();
    assertThat(statuses).hasSize(2);
    assertThat(statuses).allSatisfy(s -> assertThat(s).isIn(201, 409));
    assertThat(statuses).contains(201);
    // Every 201 carries the same stored response: one logical operation.
    assertThat(bodies.stream().distinct().count()).isEqualTo(1);
    assertThat(mock.getProcessedCount()).isEqualTo(1);
    assertThat(paymentRepo.count()).isEqualTo(paymentsBefore + 1);

    // Test-teardown: the winning booking is CONFIRMED (seat SOLD); remove the
    // committed footprint so the shared seed stays reusable.
    holdTimes.releaseCommittedBooking(bookingId);
  }

  @Test
  @Transactional
  void different_key_cannot_create_second_payment() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    seedAuthorizing(user.getId(), bookingId);

    try {
      paymentService.initiatePayment(user.getId(), bookingId, key());
      assertThat(false).as("expected ConflictException").isTrue();
    } catch (com.ticketpay.shared.error.ConflictException expected) {
      assertThat(paymentRepo.findByBookingId(bookingId)).isPresent();
      assertThat(mock.getProcessedCount()).isEqualTo(0);
    }
  }

  @Test
  @Transactional
  void fingerprint_mismatch_returns_422() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingA = bookOneSeat(userId);
    UUID bookingB = bookOneSeat(userId);
    String idemKey = key();

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingA))))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingB))))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("UNPROCESSABLE"));
  }

  @Test
  @Transactional
  void missing_key_returns_400() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    UUID bookingId = bookOneSeat(userId);

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @Transactional
  void foreign_booking_returns_404() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenB = tokenFor(emailB);
    tokenFor(emailA);
    UUID userIdA = userIdOf(emailA);
    UUID bookingId = bookOneSeat(userIdA);

    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + tokenB)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(bookingId))))
        .andExpect(status().isNotFound());
  }

  @Test
  @Transactional
  void non_payable_bookings_return_409() throws Exception {
    String email = "payer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);

    UUID confirmedId = bookOneSeat(userId);
    bookings.confirmBooking(confirmedId);
    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(confirmedId))))
        .andExpect(status().isConflict());

    UUID pendingId = bookOneSeat(userId);
    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(pendingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);
    mvc.perform(
            post("/api/v1/payments")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InitiatePaymentRequest(pendingId))))
        .andExpect(status().isConflict());
  }

  // ---- expiry interaction ----

  @Test
  @Transactional
  void unknown_does_not_block_booking_expiry() throws Exception {
    mock.setBehavior(MockPaymentProvider.MockBehavior.SLEEP_NO_RECORD);
    mock.setSleepMs(10_000);
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    paymentService.initiatePayment(user.getId(), bookingId, key());

    Payment payment = paymentRepo.findByBookingId(bookingId).orElseThrow();
    assertThat(payment.getStatus().name()).isEqualTo("UNKNOWN");

    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);

    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    assertThat(paymentRepo.findById(payment.getId()).orElseThrow().getStatus().name())
        .isEqualTo("UNKNOWN");
  }

  @Test
  @Transactional
  void decline_then_expiry_releases_seats() {
    mock.setBehavior(MockPaymentProvider.MockBehavior.DECLINED);
    User user = newUser("payer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    paymentService.initiatePayment(user.getId(), bookingId, key());

    assertThat(paymentRepo.findByBookingId(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);

    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
    assertThat(paymentRepo.findByBookingId(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("FAILED");
  }

  // ---- crash recovery (seeded durable state, no timing) ----

  @Test
  @Transactional
  void stale_no_record_safe_redrive() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAuthorizing(user.getId(), bookingId);
    IdempotencyKey seeded =
        seedKey(user.getId(), key(), bookingId, payment.getId());
    em.flush();
    em.clear();
    holdTimes.backdateKeyCreated(seeded.getId(), Instant.now().minusSeconds(300));

    var result = paymentService.initiatePayment(user.getId(), bookingId, seeded.getKey());

    assertThat(result.httpStatus()).isEqualTo(201);
    assertThat(result.body().status()).isEqualTo("SUCCEEDED");
    assertThat(mock.getProcessedCount()).isEqualTo(1);
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
  }

  @Test
  @Transactional
  void stale_success_does_not_charge_again() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAuthorizing(user.getId(), bookingId);
    mock.rehearse(payment.getProviderRef(), PaymentProvider.ChargeResult.ok());
    IdempotencyKey seeded =
        seedKey(user.getId(), key(), bookingId, payment.getId());
    em.flush();
    em.clear();
    holdTimes.backdateKeyCreated(seeded.getId(), Instant.now().minusSeconds(300));

    var result = paymentService.initiatePayment(user.getId(), bookingId, seeded.getKey());

    assertThat(result.body().status()).isEqualTo("SUCCEEDED");
    assertThat(mock.getProcessedCount()).isEqualTo(0);
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
  }

  @Test
  @Transactional
  void ambiguous_lookup_stays_unknown() {
    mock.setLookupThrows(true);
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAuthorizing(user.getId(), bookingId);
    IdempotencyKey seeded =
        seedKey(user.getId(), key(), bookingId, payment.getId());
    em.flush();
    em.clear();
    holdTimes.backdateKeyCreated(seeded.getId(), Instant.now().minusSeconds(300));

    var result = paymentService.initiatePayment(user.getId(), bookingId, seeded.getKey());

    assertThat(result.body().status()).isEqualTo("UNKNOWN");
    assertThat(mock.getProcessedCount()).isEqualTo(0);
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("PENDING_PAYMENT");
  }

  @Test
  @Transactional
  void late_success_never_confirms_expired_booking() {
    User user = newUser("payer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    Payment payment = seedAuthorizing(user.getId(), bookingId);
    IdempotencyKey seeded =
        seedKey(user.getId(), key(), bookingId, payment.getId());

    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);

    var result =
        paymentService.finishAttempt(
            payment.getId(),
            seeded.getId(),
            new PaymentService.ProviderCall(AttemptOutcome.SUCCESS, 10, null));

    assertThat(result.httpStatus()).isEqualTo(409);
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
  void expired_key_over_unresolved_payment_blocks_new_charge() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    Payment payment = seedAuthorizing(user.getId(), bookingId);
    IdempotencyKey seeded =
        seedKey(user.getId(), key(), bookingId, payment.getId());
    em.flush();
    em.clear();
    holdTimes.backdateKeyCreated(seeded.getId(), Instant.now().minusSeconds(300));
    holdTimes.backdateKeyExpiry(seeded.getId(), Instant.now().minusSeconds(60));

    try {
      paymentService.initiatePayment(user.getId(), bookingId, seeded.getKey());
      assertThat(false).as("expected ConflictException").isTrue();
    } catch (com.ticketpay.shared.error.ConflictException expected) {
      assertThat(mock.getProcessedCount()).isEqualTo(0);
      // No second payment row was created for this booking.
      assertThat(paymentRepo.findByBookingId(bookingId)).isPresent();
    }
  }

  // ---- retention cleanup ----

  @Test
  @Transactional
  void gc_preserves_unresolved_operations_past_ttl() {
    User user = newUser("payer");

    // Terminal payment first (default mock succeeds and confirms).
    UUID settledId = bookOneSeat(user.getId());
    paymentService.initiatePayment(user.getId(), settledId, key());
    UUID settledKeyId =
        keyRepo.findAll().stream()
            .filter(k -> k.getPaymentId() != null
                && k.getPaymentId()
                    .equals(paymentRepo.findByBookingId(settledId).orElseThrow().getId()))
            .findFirst()
            .orElseThrow()
            .getId();
    assertThat(paymentRepo.findByBookingId(settledId).orElseThrow().getStatus().name())
        .isEqualTo("SUCCEEDED");

    // Then an unresolved one (provider never answers).
    mock.setBehavior(MockPaymentProvider.MockBehavior.SLEEP_NO_RECORD);
    mock.setSleepMs(10_000);
    UUID pendingId = bookOneSeat(user.getId());
    paymentService.initiatePayment(user.getId(), pendingId, key());
    UUID pendingKeyId =
        keyRepo.findAll().stream()
            .filter(k -> k.getPaymentId() != null
                && k.getPaymentId()
                    .equals(paymentRepo.findByBookingId(pendingId).orElseThrow().getId()))
            .findFirst()
            .orElseThrow()
            .getId();
    assertThat(paymentRepo.findByBookingId(pendingId).orElseThrow().getStatus().name())
        .isEqualTo("UNKNOWN");

    em.flush();
    em.clear();
    // Both keys look 25h old; only the terminal one may be collected.
    holdTimes.backdateKeyExpiry(settledKeyId, Instant.now().minusSeconds(25 * 3600 + 60));
    holdTimes.backdateKeyExpiry(pendingKeyId, Instant.now().minusSeconds(25 * 3600 + 60));

    int removed = paymentService.purgeExpiredKeys();
    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThat(keyRepo.findById(settledKeyId)).isEmpty();
    assertThat(keyRepo.findById(pendingKeyId)).isPresent();
  }

  @Test
  @Transactional
  void expired_key_for_terminal_payment_is_purged_at_key_ttl_boundary() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    paymentService.initiatePayment(user.getId(), bookingId, key());
    Payment payment = paymentRepo.findByBookingId(bookingId).orElseThrow();
    IdempotencyKey keyRow =
        keyRepo.findAll().stream()
            .filter(k -> payment.getId().equals(k.getPaymentId()))
            .findFirst()
            .orElseThrow();
    em.flush();
    em.clear();
    // Only one hour past expiry: the retention window must not silently be 2x TTL.
    holdTimes.backdateKeyExpiry(keyRow.getId(), Instant.now().minusSeconds(3600));

    int removed = paymentService.purgeExpiredKeys();

    assertThat(removed).isGreaterThanOrEqualTo(1);
    assertThat(keyRepo.findById(keyRow.getId())).isEmpty();
  }

  @Test
  void financial_history_blocks_payment_deletion() {
    User user = newUser("payer");
    UUID bookingId = bookOneSeat(user.getId());
    paymentService.initiatePayment(user.getId(), bookingId, key());
    UUID paymentId = paymentRepo.findByBookingId(bookingId).orElseThrow().getId();
    User admin =
        users.save(
            new User(
                "admin-" + UUID.randomUUID() + "@example.com",
                passwords.encode("password123"),
                Role.ADMIN));
    try {
      refundService.initiateRefund(admin.getId(), paymentId, "fk protection");

      // Attempts are financial history too: the V8 RESTRICT constraint blocks deletion.
      assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE id = ?", paymentId))
          .isInstanceOf(DataAccessException.class);

      // Even without attempts, refunds and reconciliation records still protect it.
      jdbc.update("DELETE FROM payment_attempts WHERE payment_id = ?", paymentId);
      assertThatThrownBy(() -> jdbc.update("DELETE FROM payments WHERE id = ?", paymentId))
          .isInstanceOf(DataAccessException.class);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM refunds WHERE payment_id = ?", Long.class, paymentId))
          .isEqualTo(1L);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM reconciliation_records WHERE payment_id = ?",
                  Long.class,
                  paymentId))
          .isEqualTo(1L);
    } finally {
      holdTimes.releaseCommittedBooking(bookingId);
    }
  }

  // ---- reads ----

  @Test
  @Transactional
  void get_payment_enforces_ownership() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    String tokenB = tokenFor(emailB);
    UUID bookingId = bookOneSeat(userIdOf(emailA));
    paymentService.initiatePayment(userIdOf(emailA), bookingId, key());
    UUID paymentId = paymentRepo.findByBookingId(bookingId).orElseThrow().getId();

    mvc.perform(get("/api/v1/payments/" + paymentId).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.paymentId").value(paymentId.toString()))
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));

    mvc.perform(get("/api/v1/payments/" + paymentId).header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isNotFound());

    mvc.perform(get("/api/v1/payments/" + paymentId)).andExpect(status().is4xxClientError());
  }
}
