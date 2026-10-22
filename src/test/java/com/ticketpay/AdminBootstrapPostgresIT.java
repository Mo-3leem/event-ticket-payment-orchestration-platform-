package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.ticketpay.identity.AdminBootstrapGate;
import com.ticketpay.identity.AdminBootstrapRunner;
import com.ticketpay.identity.AdminBootstrapService;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import jakarta.validation.Validator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Opt-in PostgreSQL coverage for the administrator bootstrap.
 *
 * <p>This class deliberately ends in {@code IT}, so ordinary Surefire discovery does not run it as
 * part of the normal test suite. The repository does not configure Failsafe, so {@code mvn verify}
 * does not run it either. Run it with {@code -Dtest=AdminBootstrapPostgresIT test} against a fresh
 * disposable database that has no {@code ADMIN}. The suite leaves the accounts it creates;
 * provision a fresh database before rerunning it.
 *
 * <p>Run the entire class against one fresh database. Method order is part of the fixture: no
 * earlier test creates an ADMIN before the concurrent smoke test, and later tests intentionally add
 * ADMIN rows without cleanup. Reordering methods or running the class against a reused database can
 * make {@code onlyAdmin()} fail.
 *
 * <p>The concurrent test is a smoke test for the final state. The lock-timeout test is the direct
 * proof that a competing bootstrap is blocked by the PostgreSQL advisory lock.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(60)
class AdminBootstrapPostgresIT extends BaseTest {

  private static final String EMAIL_DOMAIN = "@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String OTHER_PASSWORD = "another-correct-horse-battery";

  @Autowired AdminBootstrapService bootstrap;
  @Autowired UserRepository users;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired PasswordEncoder passwords;
  @Autowired Validator validator;
  @Autowired DataSource dataSource;

  @BeforeAll
  void requireFreshDatabase() {
    assertThat(users.existsByRole(Role.ADMIN))
        .as("AdminBootstrapPostgresIT requires a fresh disposable database with no ADMIN")
        .isFalse();
  }

  @Test
  @Order(1)
  void failed_insert_rolls_back_and_leaves_gate_closed() {
    String email = uniqueEmail("rollback");
    AdminBootstrapGate gate = new AdminBootstrapGate();
    UserRepository failing = delegatingRepository();
    doAnswer(
            invocation -> {
              users.saveAndFlush(invocation.getArgument(0));
              throw new IllegalStateException("forced rollback after insert");
            })
        .when(failing)
        .saveAndFlush(any(User.class));

    AdminBootstrapService service = serviceWith(failing);

    assertThatThrownBy(() -> new AdminBootstrapRunner(service, gate, email, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("forced rollback")
        .hasMessageNotContaining(PASSWORD);

    assertThat(gate.isOpen()).isFalse();
    assertThat(users.findByEmail(email)).isEmpty();
    assertThat(users.existsByRole(Role.ADMIN)).isFalse();
  }

  @Test
  @Order(2)
  void advisory_lock_timeout_rolls_back_and_leaves_gate_closed() throws Exception {
    String email = uniqueEmail("lock-timeout");
    AdminBootstrapGate gate = new AdminBootstrapGate();
    long lockKey = advisoryLockKey();

    Connection blocker = dataSource.getConnection();
    try {
      blocker.setAutoCommit(false);
      try (Statement statement = blocker.createStatement()) {
        statement.execute("SELECT pg_advisory_xact_lock(" + lockKey + ")");
      }

      assertThatThrownBy(
              () -> new AdminBootstrapRunner(bootstrap, gate, email, PASSWORD).run(null))
          .isInstanceOf(CannotAcquireLockException.class)
          .hasMessageContaining("lock timeout")
          .hasMessageNotContaining(PASSWORD);
    } finally {
      try {
        blocker.rollback();
      } finally {
        blocker.close();
      }
    }

    assertThat(gate.isOpen()).isFalse();
    assertThat(users.findByEmail(email)).isEmpty();
    assertThat(users.existsByRole(Role.ADMIN)).isFalse();
  }

  @Test
  @Order(3)
  void duplicate_email_race_recovers_as_an_existing_user_without_promotion() throws Exception {
    String email = uniqueEmail("user-race");
    String existingPassword = "existing-user-password";
    String existingHash = passwords.encode(existingPassword);
    AdminBootstrapGate gate = new AdminBootstrapGate();
    CountDownLatch insertAttempted = new CountDownLatch(1);
    CountDownLatch externalCommitted = new CountDownLatch(1);

    UserRepository racing = delegatingRepository();
    doAnswer(
            invocation -> {
              insertAttempted.countDown();
              if (!externalCommitted.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("external transaction was not committed");
              }
              return users.saveAndFlush(invocation.getArgument(0));
            })
        .when(racing)
        .saveAndFlush(any(User.class));
    AdminBootstrapService service = serviceWith(racing);

    ExecutorService pool = Executors.newSingleThreadExecutor();
    Connection external = null;
    try {
      external = dataSource.getConnection();
      external.setAutoCommit(false);
      insertUser(external, email, existingHash);
      Future<?> future =
          pool.submit(() -> new AdminBootstrapRunner(service, gate, email, PASSWORD).run(null));

      assertThat(insertAttempted.await(20, TimeUnit.SECONDS))
          .as("bootstrap should reach the insert before the external transaction commits")
          .isTrue();
      external.commit();
      externalCommitted.countDown();

      ExecutionException failure =
          catchThrowableOfType(
              () -> future.get(20, TimeUnit.SECONDS), ExecutionException.class);
      assertThat(failure).isNotNull();
      assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
      assertThat(failure.getCause())
          .hasMessageContaining("USER")
          .hasMessageNotContaining(PASSWORD)
          .hasMessageNotContaining(existingHash);
    } finally {
      externalCommitted.countDown();
      try {
        if (external != null && !external.getAutoCommit()) {
          external.rollback();
        }
      } finally {
        try {
          if (external != null) {
            external.close();
          }
        } finally {
          shutdown(pool);
        }
      }
    }

    assertThat(gate.isOpen()).isFalse();
    User existing = users.findByEmail(email).orElseThrow();
    assertThat(existing.getRole()).isEqualTo(Role.USER);
    assertThat(existing.getPasswordHash()).isEqualTo(existingHash);
    assertThat(passwords.matches(existingPassword, existing.getPasswordHash())).isTrue();
    assertThat(users.existsByRole(Role.ADMIN)).isFalse();
  }

  @Test
  @Order(4)
  void concurrent_bootstraps_create_exactly_one_normalized_admin_smoke_test(CapturedOutput output)
      throws Exception {
    String firstConfigured =
        " " + uniqueEmail("admin-race-a").toUpperCase(Locale.ROOT) + " ";
    String secondConfigured = uniqueEmail("admin-race-b").toUpperCase(Locale.ROOT);
    AdminBootstrapGate firstGate = new AdminBootstrapGate();
    AdminBootstrapGate secondGate = new AdminBootstrapGate();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> first =
          pool.submit(
              () -> {
                ready.countDown();
                await(start);
                new AdminBootstrapRunner(bootstrap, firstGate, firstConfigured, PASSWORD).run(null);
              });
      Future<?> second =
          pool.submit(
              () -> {
                ready.countDown();
                await(start);
                new AdminBootstrapRunner(bootstrap, secondGate, secondConfigured, PASSWORD).run(null);
              });

      assertThat(ready.await(20, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      first.get(20, TimeUnit.SECONDS);
      second.get(20, TimeUnit.SECONDS);
    } finally {
      start.countDown();
      shutdown(pool);
    }

    User admin = onlyAdmin();
    assertThat(admin.getEmail())
        .isIn(
            firstConfigured.trim().toLowerCase(Locale.ROOT),
            secondConfigured.trim().toLowerCase(Locale.ROOT));
    assertThat(admin.getEmail()).isEqualTo(admin.getEmail().toLowerCase(Locale.ROOT));
    assertThat(admin.getEmail()).doesNotContain(" ");
    assertThat(passwords.matches(PASSWORD, admin.getPasswordHash())).isTrue();
    assertThat(firstGate.isOpen()).isTrue();
    assertThat(secondGate.isOpen()).isTrue();
    assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(admin.getPasswordHash());
  }

  /**
   * Existing-admin restart/idempotent-skip behavior. The ADMIN is seeded directly rather than
   * created by a first bootstrap run, so this case does not cover create-then-restart.
   */
  @Test
  @Order(5)
  void existing_admin_restart_skips_creation_and_preserves_credentials() {
    String email = uniqueEmail("restart-admin");
    String originalPassword = "existing-admin-password";
    User before =
        users.saveAndFlush(new User(email, passwords.encode(originalPassword), Role.ADMIN));
    UUID id = before.getId();
    String originalHash = before.getPasswordHash();
    Instant createdAt = before.getCreatedAt();
    AdminBootstrapGate gate = new AdminBootstrapGate();

    new AdminBootstrapRunner(bootstrap, gate, before.getEmail(), OTHER_PASSWORD).run(null);

    User after = users.findById(id).orElseThrow();
    assertThat(after.getRole()).isEqualTo(Role.ADMIN);
    assertThat(after.getPasswordHash()).isEqualTo(originalHash);
    assertThat(Duration.between(createdAt, after.getCreatedAt()).abs())
        .as("createdAt remains unchanged at TIMESTAMPTZ microsecond precision")
        .isLessThan(Duration.ofNanos(1_000));
    assertThat(passwords.matches(originalPassword, after.getPasswordHash())).isTrue();
    assertThat(passwords.matches(OTHER_PASSWORD, after.getPasswordHash())).isFalse();
    assertThat(gate.isOpen()).isTrue();
  }

  @Test
  @Order(6)
  void existing_user_is_refused_without_promotion() {
    String email = uniqueEmail("existing-user");
    String existingPassword = "existing-user-password";
    long adminCountBefore = adminCount();
    User existing =
        users.saveAndFlush(new User(email, passwords.encode(existingPassword), Role.USER));
    UUID id = existing.getId();
    String hash = existing.getPasswordHash();
    AdminBootstrapGate gate = new AdminBootstrapGate();

    assertThatThrownBy(
            () -> new AdminBootstrapRunner(bootstrap, gate, email, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("USER")
        .hasMessageNotContaining(PASSWORD)
        .hasMessageNotContaining(hash);

    assertThat(gate.isOpen()).isFalse();
    User after = users.findById(id).orElseThrow();
    assertThat(after.getRole()).isEqualTo(Role.USER);
    assertThat(after.getPasswordHash()).isEqualTo(hash);
    assertThat(passwords.matches(existingPassword, after.getPasswordHash())).isTrue();
    assertThat(adminCount())
        .as("rejected bootstrap must not add an ADMIN")
        .isEqualTo(adminCountBefore);
  }

  private AdminBootstrapService serviceWith(UserRepository repository) {
    return new AdminBootstrapService(
        repository, passwords, jdbc, transactionManager, validator);
  }

  private UserRepository delegatingRepository() {
    return mock(UserRepository.class, AdditionalAnswers.delegatesTo(users));
  }

  private long adminCount() {
    return users.findAll().stream().filter(user -> user.getRole() == Role.ADMIN).count();
  }

  private User onlyAdmin() {
    List<User> admins =
        users.findAll().stream().filter(user -> user.getRole() == Role.ADMIN).toList();
    assertThat(admins).hasSize(1);
    return admins.get(0);
  }

  private void insertUser(Connection connection, String email, String hash) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'USER')")) {
      statement.setObject(1, UUID.randomUUID());
      statement.setString(2, email);
      statement.setString(3, hash);
      assertThat(statement.executeUpdate()).isEqualTo(1);
    }
  }

  private static long advisoryLockKey() {
    Object key = ReflectionTestUtils.getField(AdminBootstrapService.class, "ADVISORY_LOCK_KEY");
    assertThat(key).isInstanceOf(Number.class);
    return ((Number) key).longValue();
  }

  private static String uniqueEmail(String prefix) {
    return "day8a-" + prefix + "-" + UUID.randomUUID() + EMAIL_DOMAIN;
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Timed out waiting for the concurrent start");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting for the concurrent start", interrupted);
    }
  }

  private static void shutdown(ExecutorService pool) {
    pool.shutdownNow();
    try {
      if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
        throw new IllegalStateException("Concurrent bootstrap executor did not terminate");
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while terminating the executor", interrupted);
    }
  }
}
