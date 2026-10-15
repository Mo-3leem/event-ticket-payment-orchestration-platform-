package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.ticketpay.booking.Booking;
import com.ticketpay.booking.BookingItemRepository;
import com.ticketpay.booking.BookingRepository;
import com.ticketpay.booking.BookingService;
import com.ticketpay.booking.HoldService;
import com.ticketpay.booking.dto.CreateBookingRequest;
import com.ticketpay.booking.dto.HoldRequest;
import com.ticketpay.catalog.Event;
import com.ticketpay.catalog.EventRepository;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import com.ticketpay.identity.dto.RegisterRequest;
import com.ticketpay.shared.error.ConflictException;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestHoldTimes.class)
class BookingTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired EventRepository eventRepo;
  @Autowired EventSeatRepository eventSeats;
  @Autowired HoldService holds;
  @Autowired BookingService bookings;
  @Autowired BookingRepository bookingRepo;
  @Autowired BookingItemRepository bookingItems;
  @Autowired TestHoldTimes holdTimes;
  @PersistenceContext EntityManager em;

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

  private List<EventSeat> availableSeatsOfSameEvent(int n) {
    for (Event event : eventRepo.findAll()) {
      List<EventSeat> avail =
          eventSeats.findByEventId(event.getId()).stream()
              .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
              .limit(n)
              .toList();
      if (avail.size() == n) {
        return avail;
      }
    }
    throw new IllegalStateException("Not enough AVAILABLE seats in one event");
  }

  private EventSeat firstAvailableSeat() {
    return eventSeats.findAll().stream()
        .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No AVAILABLE seat in seed"));
  }

  private String postBooking(String token, List<UUID> holdIds) throws Exception {
    return mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(holdIds))))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  // ---- creation ----

  @Test
  @Transactional
  void create_booking_returns_201_pending() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);

    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(List.of(seat.getId())))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
        .andExpect(jsonPath("$.reference").exists())
        .andExpect(jsonPath("$.expiresAt").exists())
        .andExpect(jsonPath("$.eventSeatIds.length()").value(1));
  }

  @Test
  @Transactional
  void total_is_computed_from_server_prices() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    List<EventSeat> seats = availableSeatsOfSameEvent(2);
    Event event = seats.get(0).getEvent();
    for (EventSeat seat : seats) {
      holds.acquire(seat.getId(), userId);
    }

    String body =
        postBooking(token, seats.stream().map(EventSeat::getId).toList());
    JsonNode json = objectMapper.readTree(body);
    assertThat(json.get("totalMinor").asLong()).isEqualTo(2 * event.getPriceMinor());
    assertThat(json.get("currency").asText()).isEqualTo(event.getCurrency());
  }

  @Test
  @Transactional
  void expired_hold_is_rejected_and_nothing_persists() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);
    em.flush();
    em.clear();
    holdTimes.expireInPast(seat.getId(), Instant.now().minusSeconds(60));

    long before = bookingRepo.count();
    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(List.of(seat.getId())))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
    assertThat(bookingRepo.count()).isEqualTo(before);
  }

  @Test
  @Transactional
  void other_users_hold_is_rejected() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    tokenFor(emailB);
    UUID userIdB = userIdOf(emailB);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userIdB);

    long before = bookingRepo.count();
    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(List.of(seat.getId())))))
        .andExpect(status().isConflict());
    assertThat(bookingRepo.count()).isEqualTo(before);
  }

  @Test
  @Transactional
  void mixed_event_holds_are_rejected() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    List<EventSeat> all = eventSeats.findAll();
    EventSeat first = all.get(0);
    EventSeat otherEventSeat =
        all.stream()
            .filter(es -> !es.getEvent().getId().equals(first.getEvent().getId()))
            .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
            .findFirst()
            .orElseThrow();
    EventSeat firstAvailable =
        all.stream()
            .filter(es -> es.getEvent().getId().equals(first.getEvent().getId()))
            .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
            .findFirst()
            .orElseThrow();
    holds.acquire(firstAvailable.getId(), userId);
    holds.acquire(otherEventSeat.getId(), userId);

    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateBookingRequest(
                            List.of(firstAvailable.getId(), otherEventSeat.getId())))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
  }

  @Test
  @Transactional
  void duplicate_hold_ids_are_rejected_and_nothing_persists() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);

    long before = bookingRepo.count();
    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CreateBookingRequest(List.of(seat.getId(), seat.getId())))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    assertThat(bookingRepo.count()).isEqualTo(before);
  }

  @Test
  @Transactional
  void empty_hold_list_returns_validation_failed_shape() throws Exception {
    String email = "buyer-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);

    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(List.of()))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.fieldErrors").isArray())
        .andExpect(jsonPath("$.correlationId").exists());
  }

  // ---- lifecycle (service-level: confirmation is internal-only, no endpoint) ----

  @Test
  @Transactional
  void confirm_marks_confirmed_and_sold() {
    User user = newUser("buyer");
    List<EventSeat> seats = availableSeatsOfSameEvent(2);
    for (EventSeat seat : seats) {
      holds.acquire(seat.getId(), user.getId());
    }
    UUID bookingId =
        bookings
            .createBooking(
                user.getId(), seats.stream().map(EventSeat::getId).toList())
            .bookingId();

    bookings.confirmBooking(bookingId);

    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    for (EventSeat seat : seats) {
      assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
          .isEqualTo(EventSeatStatus.SOLD);
    }
  }

  @Test
  @Transactional
  void duplicate_confirm_is_idempotent() {
    User user = newUser("buyer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();

    bookings.confirmBooking(bookingId);
    bookings.confirmBooking(bookingId);

    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.SOLD);
  }

  @Test
  @Transactional
  void confirm_expired_booking_is_rejected() {
    User user = newUser("buyer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);

    assertThrows(ConflictException.class, () -> bookings.confirmBooking(bookingId));
  }

  @Test
  @Transactional
  void expiry_releases_seats_and_expires_booking() {
    User user = newUser("buyer");
    List<EventSeat> seats = availableSeatsOfSameEvent(2);
    for (EventSeat seat : seats) {
      holds.acquire(seat.getId(), user.getId());
    }
    UUID bookingId =
        bookings
            .createBooking(
                user.getId(), seats.stream().map(EventSeat::getId).toList())
            .bookingId();
    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(bookingId, Instant.now().minusSeconds(60));

    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    for (EventSeat seat : seats) {
      assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
          .isEqualTo(EventSeatStatus.AVAILABLE);
    }
  }

  @Test
  @Transactional
  void expiry_never_releases_sold_seats() {
    User user = newUser("buyer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    bookings.confirmBooking(bookingId);

    assertThat(holds.releaseExpired()).isZero();
    assertThat(bookings.expireBookings()).isZero();
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.SOLD);
  }

  @Test
  @Transactional
  void confirm_then_expire_leaves_confirmed_intact() {
    User user = newUser("buyer");
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());
    UUID bookingId = bookings.createBooking(user.getId(), List.of(seat.getId())).bookingId();
    bookings.confirmBooking(bookingId);

    assertThat(bookings.expireBookings()).isZero();
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
  }

  // ---- ownership ----

  @Test
  @Transactional
  void users_list_only_their_own_holds_and_bookings() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    String tokenB = tokenFor(emailB);
    UUID userIdA = userIdOf(emailA);
    List<EventSeat> seats = availableSeatsOfSameEvent(2);
    holds.acquire(seats.get(0).getId(), userIdA);
    holds.acquire(seats.get(1).getId(), userIdA);
    bookings.createBooking(userIdA, List.of(seats.get(0).getId()));

    mvc.perform(get("/api/v1/holds/mine").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(2));
    mvc.perform(get("/api/v1/holds/mine").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));

    mvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(1))
        .andExpect(jsonPath("$.content[0].status").value("PENDING_PAYMENT"));
    mvc.perform(get("/api/v1/bookings/me").header("Authorization", "Bearer " + tokenB))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content.length()").value(0));
  }

  @Test
  @Transactional
  void release_own_hold_and_rehold() throws Exception {
    String email = "holder-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);

    mvc.perform(
            delete("/api/v1/holds/" + seat.getId()).header("Authorization", "Bearer " + token))
        .andExpect(status().isNoContent());

    mvc.perform(
            post("/api/v1/holds")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldRequest(seat.getId()))))
        .andExpect(status().isCreated());
  }

  @Test
  @Transactional
  void release_other_users_hold_returns_404() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    tokenFor(emailB);
    UUID userIdB = userIdOf(emailB);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userIdB);

    mvc.perform(
            delete("/api/v1/holds/" + seat.getId()).header("Authorization", "Bearer " + tokenA))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  @Transactional
  void release_missing_hold_returns_404() throws Exception {
    String email = "holder-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);

    mvc.perform(
            delete("/api/v1/holds/" + UUID.randomUUID())
                .header("Authorization", "Bearer " + token))
        .andExpect(status().isNotFound());
  }

  // ---- concurrency ----

  @Test
  void concurrent_consume_of_same_hold_creates_single_booking() throws Exception {
    User user = newUser("buyer");
    EventSeat target = firstAvailableSeat();
    holds.acquire(target.getId(), user.getId());
    long countBefore = bookingRepo.count();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();
    List<Throwable> failures = new ArrayList<>();
    AtomicReference<UUID> bookingId = new AtomicReference<>();

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
                  UUID id =
                      bookings.createBooking(user.getId(), List.of(target.getId())).bookingId();
                  bookingId.compareAndSet(null, id);
                  successes.incrementAndGet();
                } catch (ConflictException ex) {
                  conflicts.incrementAndGet();
                } catch (RuntimeException ex) {
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
    assertThat(successes.get()).isEqualTo(1);
    assertThat(conflicts.get()).isEqualTo(1);
    assertThat(bookingRepo.count()).isEqualTo(countBefore + 1);

    // Rerun safety: expire the won booking so the seat returns to AVAILABLE.
    holdTimes.expireBookingInPast(bookingId.get(), Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);
    assertThat(eventSeats.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
  }

  // ---- H1 regression: expired seats become bookable again, history preserved ----

  @Test
  @Transactional
  void expired_seat_can_be_rebooked_with_history_preserved() throws Exception {
    String email = "rebook-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();

    // 1-2. Hold, then book.
    holds.acquire(seat.getId(), userId);
    UUID oldBookingId =
        bookings.createBooking(userId, List.of(seat.getId())).bookingId();

    // 3. Expire via the test-supported backdate mechanism.
    em.flush();
    em.clear();
    holdTimes.expireBookingInPast(oldBookingId, Instant.now().minusSeconds(60));
    assertThat(bookings.expireBookings()).isGreaterThanOrEqualTo(1);

    // 4. Old booking EXPIRED, seat AVAILABLE, historical item kept with released=true.
    assertThat(bookingRepo.findById(oldBookingId).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
    assertThat(bookingItems.findByBookingId(oldBookingId)).hasSize(1);
    assertThat(bookingItems.findByBookingId(oldBookingId).get(0).isReleased()).isTrue();

    // 5-6. New hold on the same seat, then a new booking via HTTP.
    holds.acquire(seat.getId(), userId);
    mvc.perform(
            post("/api/v1/bookings")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateBookingRequest(List.of(seat.getId())))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));

    // 7-8. History intact: old booking still EXPIRED with its released item,
    // new booking has its own live item.
    assertThat(bookingRepo.findById(oldBookingId).orElseThrow().getStatus().name())
        .isEqualTo("EXPIRED");
    assertThat(bookingItems.findByBookingId(oldBookingId).get(0).isReleased()).isTrue();
    UUID newBookingId =
        bookingRepo.findAll().stream()
            .filter(b -> !b.getId().equals(oldBookingId))
            .filter(b -> b.getUserId().equals(userId))
            .map(Booking::getId)
            .findFirst()
            .orElseThrow();
    assertThat(bookingItems.findByBookingId(newBookingId)).hasSize(1);
    assertThat(bookingItems.findByBookingId(newBookingId).get(0).isReleased()).isFalse();
  }

  // ---- M1 regression: booked holds cannot be released ----

  @Test
  @Transactional
  void release_booked_hold_returns_409_and_booking_survives() throws Exception {
    String email = "owner-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    UUID userId = userIdOf(email);
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), userId);
    UUID bookingId = bookings.createBooking(userId, List.of(seat.getId())).bookingId();

    // Owner attempts to release a hold consumed by their live booking.
    mvc.perform(
            delete("/api/v1/holds/" + seat.getId()).header("Authorization", "Bearer " + token))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));

    // Booking untouched, seat still held.
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("PENDING_PAYMENT");
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.HELD);

    // The booking still confirms normally through the internal operation.
    bookings.confirmBooking(bookingId);
    assertThat(bookingRepo.findById(bookingId).orElseThrow().getStatus().name())
        .isEqualTo("CONFIRMED");
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.SOLD);
  }
}
