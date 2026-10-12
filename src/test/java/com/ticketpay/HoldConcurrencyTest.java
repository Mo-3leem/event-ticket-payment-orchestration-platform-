package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketpay.booking.HoldService;
import com.ticketpay.catalog.EventSeat;
import com.ticketpay.catalog.EventSeatRepository;
import com.ticketpay.catalog.EventSeatStatus;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

@Import(TestHoldTimes.class)
class HoldConcurrencyTest extends BaseTest {

  @Autowired HoldService holds;
  @Autowired EventSeatRepository eventSeats;
  @Autowired UserRepository users;
  @Autowired PasswordEncoder passwords;
  @Autowired TestHoldTimes holdTimes;

  @Test
  void only_one_thread_wins_the_same_event_seat() throws Exception {
    EventSeat target =
        eventSeats.findAll().stream()
            .filter(es -> es.getStatus() == EventSeatStatus.AVAILABLE)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No AVAILABLE seat in seed"));

    int threads = 8;
    List<UUID> userIds = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      User u =
          users.save(
              new User(
                  "race-" + UUID.randomUUID() + "@example.com",
                  passwords.encode("password123"),
                  Role.USER));
      userIds.add(u.getId());
    }

    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger successes = new AtomicInteger();
    AtomicInteger conflicts = new AtomicInteger();

    List<Future<?>> futures = new ArrayList<>();
    for (UUID userId : userIds) {
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
                  holds.acquire(target.getId(), userId);
                  successes.incrementAndGet();
                } catch (Exception ex) {
                  conflicts.incrementAndGet();
                }
              }));
    }

    ready.await();
    start.countDown();
    for (Future<?> f : futures) {
      f.get();
    }
    pool.shutdown();

    assertThat(successes.get()).isEqualTo(1);
    assertThat(conflicts.get()).isEqualTo(threads - 1);
    assertThat(eventSeats.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.HELD);

    // Rerun safety: free the won seat so repeated runs never exhaust the 20-row seed.
    holdTimes.expireInPast(target.getId(), java.time.Instant.now().minusSeconds(60));
    holds.releaseExpired();
    assertThat(eventSeats.findById(target.getId()).orElseThrow().getStatus())
        .isEqualTo(EventSeatStatus.AVAILABLE);
  }
}
