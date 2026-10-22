package com.ticketpay.identity;

import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

@Service
public class AdminBootstrapService {

  private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

  private static final int MIN_PASSWORD_CHARS = 16;
  private static final int MAX_BCRYPT_BYTES = 72;
  private static final long ADVISORY_LOCK_KEY = 0x5449434B4554L;
  private static final String LOCK_TIMEOUT = "5s";

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Validator validator;

  public AdminBootstrapService(
      UserRepository users,
      PasswordEncoder passwords,
      JdbcTemplate jdbc,
      PlatformTransactionManager transactionManager,
      Validator validator) {
    this.users = users;
    this.passwords = passwords;
    this.jdbc = jdbc;
    this.tx = new TransactionTemplate(transactionManager);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    this.validator = validator;
  }

  public void bootstrap(String configuredEmail, String configuredPassword) {
    boolean hasEmail = StringUtils.hasText(configuredEmail);
    boolean hasPassword = StringUtils.hasText(configuredPassword);
    if (!hasEmail && !hasPassword) {
      return;
    }
    if (hasEmail != hasPassword) {
      throw new IllegalStateException(
          "Admin bootstrap requires both APP_BOOTSTRAP_ADMIN_EMAIL and"
              + " APP_BOOTSTRAP_ADMIN_PASSWORD");
    }

    String email = normalizeEmail(configuredEmail);
    validateEmail(email);
    validatePassword(configuredPassword);

    try {
      logOutcome(tx.execute(status -> provision(email, configuredPassword)));
    } catch (DataIntegrityViolationException conflict) {
      logOutcome(tx.execute(status -> resolveConflict(email, conflict)));
    }
  }

  private Outcome provision(String email, String password) {
    acquireBootstrapLock();

    Optional<User> existing = users.findByEmail(email);
    if (existing.isPresent()) {
      if (existing.get().getRole() == Role.USER) {
        throw new IllegalStateException(
            "Admin bootstrap refused: the configured email already belongs to a USER;"
                + " choose a different email");
      }
      return Outcome.SKIPPED_EMAIL_ADMIN;
    }

    if (users.existsByRole(Role.ADMIN)) {
      return Outcome.SKIPPED_ADMIN_EXISTS;
    }

    users.saveAndFlush(new User(email, passwords.encode(password), Role.ADMIN));
    return Outcome.CREATED;
  }

  private Outcome resolveConflict(String email, DataIntegrityViolationException conflict) {
    acquireBootstrapLock();
    User existing = users.findByEmail(email).orElse(null);
    if (existing != null) {
      if (existing.getRole() == Role.USER) {
        throw new IllegalStateException(
            "Admin bootstrap refused: the configured email already belongs to a USER;"
                + " choose a different email",
            conflict);
      }
      return Outcome.SKIPPED_EMAIL_ADMIN;
    }

    if (users.existsByRole(Role.ADMIN)) {
      return Outcome.SKIPPED_ADMIN_EXISTS;
    }

    throw new IllegalStateException(
        "Admin bootstrap failed due to a concurrent database change; no account was created",
        conflict);
  }

  private void logOutcome(Outcome outcome) {
    switch (outcome) {
      case CREATED -> log.info("Admin bootstrap created the initial ADMIN account");
      case SKIPPED_EMAIL_ADMIN ->
          log.info("Admin bootstrap skipped: the configured email is already an ADMIN");
      case SKIPPED_ADMIN_EXISTS -> log.info("Admin bootstrap skipped: an ADMIN already exists");
    }
  }

  private void acquireBootstrapLock() {
    jdbc.execute("SET LOCAL lock_timeout = '" + LOCK_TIMEOUT + "'");
    try {
      jdbc.execute("SELECT pg_advisory_xact_lock(" + ADVISORY_LOCK_KEY + ")");
    } catch (UncategorizedSQLException ex) {
      if (ex.getSQLException() != null
          && "55P03".equals(ex.getSQLException().getSQLState())) {
        throw new CannotAcquireLockException("Admin bootstrap advisory lock timeout", ex);
      }
      throw ex;
    }
  }

  private String normalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private void validateEmail(String email) {
    if (!validator.validate(new EmailValue(email)).isEmpty()) {
      throw new IllegalStateException(
          "APP_BOOTSTRAP_ADMIN_EMAIL must be a valid email address");
    }
  }

  private void validatePassword(String password) {
    if (password.length() < MIN_PASSWORD_CHARS) {
      throw new IllegalStateException(
          "APP_BOOTSTRAP_ADMIN_PASSWORD must contain at least "
              + MIN_PASSWORD_CHARS
              + " characters");
    }
    if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BCRYPT_BYTES) {
      throw new IllegalStateException(
          "APP_BOOTSTRAP_ADMIN_PASSWORD must be at most "
              + MAX_BCRYPT_BYTES
              + " UTF-8 bytes");
    }
  }

  private record EmailValue(@NotBlank @Email @Size(max = 255) String email) {}

  private enum Outcome {
    CREATED,
    SKIPPED_EMAIL_ADMIN,
    SKIPPED_ADMIN_EXISTS
  }
}
