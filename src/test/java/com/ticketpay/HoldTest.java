package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketpay.booking.HoldService;
import com.ticketpay.booking.dto.HoldRequest;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import com.ticketpay.identity.dto.RegisterRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestHoldTimes.class)
class HoldTest extends BaseTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired EventSeatRepository eventSeats;
  @Autowired HoldService holds;
  @Autowired TestHoldTimes holdTimes;
  @PersistenceContext EntityManager em;

  private User newUser() {
    User user =
        new User(
            "hold-" + UUID.randomUUID() + "@example.com", passwords.encode("password123"), Role.USER);
    return users.save(user);
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

  private EventSeat firstAvailableSeat() {
    return eventSeats.findAll().stream()
        .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("No AVAILABLE seat in seed"));
  }

  @Test
  @Transactional
  void acquire_hold_returns_201() throws Exception {
    String email = "holder-" + UUID.randomUUID() + "@example.com";
    String token = tokenFor(email);
    EventSeat seat = firstAvailableSeat();

    mvc.perform(
            post("/api/v1/holds")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldRequest(seat.getId()))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("HELD"))
        .andExpect(jsonPath("$.expiresAt").exists());
  }

  @Test
  @Transactional
  void second_hold_on_same_event_seat_returns_409() throws Exception {
    String emailA = "a-" + UUID.randomUUID() + "@example.com";
    String emailB = "b-" + UUID.randomUUID() + "@example.com";
    String tokenA = tokenFor(emailA);
    String tokenB = tokenFor(emailB);
    EventSeat seat = firstAvailableSeat();

    mvc.perform(
            post("/api/v1/holds")
                .header("Authorization", "Bearer " + tokenA)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldRequest(seat.getId()))))
        .andExpect(status().isCreated());

    mvc.perform(
            post("/api/v1/holds")
                .header("Authorization", "Bearer " + tokenB)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldRequest(seat.getId()))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONFLICT"));
  }

  @Test
  @Transactional
  void same_physical_seat_is_independently_sellable_per_event() {
    // Find two event_seats sharing one physical seat_id but different event_ids.
    List<EventSeat> all = eventSeats.findAll();
    EventSeat first = all.get(0);
    UUID physicalSeatId = first.getSeat().getId();
    List<EventSeat> siblings =
        all.stream().filter(es -> es.getSeat().getId().equals(physicalSeatId)).toList();

    assertThat(siblings).hasSize(2);
    assertThat(siblings.get(0).getEvent().getId())
        .isNotEqualTo(siblings.get(1).getEvent().getId());

    User userA = newUser();
    User userB = newUser();

    // Both must succeed: independent inventory rows.
    holds.acquire(siblings.get(0).getId(), userA.getId());
    holds.acquire(siblings.get(1).getId(), userB.getId());

    assertThat(eventSeats.findById(siblings.get(0).getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.HELD);
    assertThat(eventSeats.findById(siblings.get(1).getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.HELD);
  }

  @Test
  @Transactional
  void expired_hold_is_released_by_job() {
    User user = newUser();
    EventSeat seat = firstAvailableSeat();
    holds.acquire(seat.getId(), user.getId());

    // Force expiry into the past, then run the release path.
    EventSeat held = eventSeats.findById(seat.getId()).orElseThrow();
    assertThat(held.getStatus()).isEqualTo(EventSeatStatus.HELD);

    // Simulate time passing by moving the timestamp back (test-only mutation).
    // flush()+clear() must come FIRST: acquire() only staged JPA changes, and the
    // JDBC backdate needs a real row; clear() also detaches the entity so the
    // later release query cannot be shadowed by stale persistence-context state.
    em.flush();
    em.clear();
    holdTimes.expireInPast(seat.getId(), Instant.now().minusSeconds(60));

    int released = holds.releaseExpired();
    assertThat(released).isGreaterThanOrEqualTo(1);
    assertThat(eventSeats.findById(seat.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
  }

  @Test
  void hold_without_token_is_rejected() throws Exception {
    EventSeat seat = firstAvailableSeat();
    mvc.perform(
            post("/api/v1/holds")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new HoldRequest(seat.getId()))))
        .andExpect(status().is4xxClientError());
  }
}
