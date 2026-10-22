package com.ticketpay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ticketpay.identity.AdminBootstrapGate;
import com.ticketpay.identity.AdminBootstrapRunner;
import com.ticketpay.identity.AdminBootstrapService;
import com.ticketpay.identity.AuthService;
import com.ticketpay.identity.JwtService;
import com.ticketpay.identity.Role;
import com.ticketpay.identity.User;
import com.ticketpay.identity.UserRepository;
import com.ticketpay.identity.dto.LoginRequest;
import com.ticketpay.identity.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class AdminBootstrapTest {

  private static final String EMAIL = "admin@example.com";
  private static final String PASSWORD = "correct-horse-battery-staple";
  private static final String OLD_PASSWORD = "existing-password";
  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Mock UserRepository users;
  @Mock JdbcTemplate jdbc;
  @Mock PlatformTransactionManager transactionManager;

  private final PasswordEncoder passwords = new BCryptPasswordEncoder(4);
  private AdminBootstrapService service;

  @BeforeEach
  void setUp() {
    service = new AdminBootstrapService(users, passwords, jdbc, transactionManager, VALIDATOR);
  }

  private void stubTransaction() {
    when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
  }

  private User existingUser(Role role) {
    return new User(EMAIL, passwords.encode(OLD_PASSWORD), role);
  }

  @Test
  void no_configuration_is_noop() {
    service.bootstrap("", "");

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void only_email_fails_safely_without_touching_the_database() {
    assertThatThrownBy(() -> service.bootstrap(EMAIL, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APP_BOOTSTRAP_ADMIN_PASSWORD")
        .hasMessageNotContaining(PASSWORD);

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void only_password_fails_safely_without_touching_the_database() {
    assertThatThrownBy(() -> service.bootstrap("", PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("APP_BOOTSTRAP_ADMIN_EMAIL");

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void short_password_fails_safely_without_touching_the_database() {
    String shortPassword = "123456789012345";

    assertThatThrownBy(() -> service.bootstrap(EMAIL, shortPassword))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at least 16 characters")
        .hasMessageNotContaining(shortPassword);

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void password_over_72_utf8_bytes_fails_safely_without_touching_the_database() {
    String longPassword = "é".repeat(40);
    assertThat(longPassword.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);

    assertThatThrownBy(() -> service.bootstrap(EMAIL, longPassword))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("at most 72 UTF-8 bytes")
        .hasMessageNotContaining(longPassword);

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void invalid_email_fails_safely_without_touching_the_database() {
    assertThatThrownBy(() -> service.bootstrap("not-an-email", PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("valid email address");

    verifyNoInteractions(users, jdbc, transactionManager);
  }

  @Test
  void valid_configuration_creates_an_admin_with_a_bcrypt_hash() {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.bootstrap(" Admin@Example.COM ", PASSWORD);

    ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
    verify(users).saveAndFlush(created.capture());
    assertThat(created.getValue().getEmail()).isEqualTo(EMAIL);
    assertThat(created.getValue().getRole()).isEqualTo(Role.ADMIN);
    assertThat(created.getValue().getPasswordHash()).isNotEqualTo(PASSWORD);
    assertThat(passwords.matches(PASSWORD, created.getValue().getPasswordHash())).isTrue();
    verify(jdbc).execute("SET LOCAL lock_timeout = '5s'");
    verify(jdbc).execute(contains("pg_advisory_xact_lock"));
    verify(transactionManager).commit(any());
  }

  @Test
  void existing_admin_is_not_duplicated_or_modified() {
    stubTransaction();
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.existsByRole(Role.ADMIN)).thenReturn(true);

    service.bootstrap(EMAIL, PASSWORD);

    verify(users, never()).saveAndFlush(any(User.class));
    verify(transactionManager).commit(any());
  }

  @Test
  void existing_admin_at_the_configured_email_is_not_modified() {
    stubTransaction();
    User existing = existingUser(Role.ADMIN);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    service.bootstrap(EMAIL, PASSWORD);

    assertThat(existing.getRole()).isEqualTo(Role.ADMIN);
    assertThat(passwords.matches(OLD_PASSWORD, existing.getPasswordHash())).isTrue();
    verify(users, never()).saveAndFlush(any(User.class));
  }

  @Test
  void advisory_lock_is_acquired_before_any_bootstrap_check() {
    stubTransaction();
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.existsByRole(Role.ADMIN)).thenReturn(true);

    service.bootstrap(EMAIL, PASSWORD);

    InOrder order = inOrder(jdbc, users);
    order.verify(jdbc).execute("SET LOCAL lock_timeout = '5s'");
    order.verify(jdbc).execute(contains("pg_advisory_xact_lock"));
    order.verify(users).findByEmail(EMAIL);
    order.verify(users).existsByRole(Role.ADMIN);
  }

  @Test
  void lock_timeout_fails_before_any_bootstrap_check_or_write() {
    stubTransaction();
    doAnswer(
            invocation -> {
              String sql = invocation.getArgument(0);
              if (sql.contains("pg_advisory_xact_lock")) {
                throw new CannotAcquireLockException("advisory lock timeout");
              }
              return null;
            })
        .when(jdbc)
        .execute(any(String.class));

    assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD))
        .isInstanceOf(CannotAcquireLockException.class)
        .hasMessageContaining("advisory lock timeout");

    verify(jdbc).execute("SET LOCAL lock_timeout = '5s'");
    verify(users, never()).findByEmail(any());
    verify(users, never()).existsByRole(any(Role.class));
    verify(users, never()).saveAndFlush(any(User.class));
    verify(transactionManager).rollback(any());
    verify(transactionManager, never()).commit(any());
  }

  @Test
  void existing_user_is_not_promoted_or_modified() {
    stubTransaction();
    User existing = existingUser(Role.USER);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("USER")
        .hasMessageNotContaining(PASSWORD);

    assertThat(existing.getRole()).isEqualTo(Role.USER);
    assertThat(passwords.matches(OLD_PASSWORD, existing.getPasswordHash())).isTrue();
    verify(users, never()).saveAndFlush(any(User.class));
  }

  @Test
  void existing_user_fails_closed_even_when_an_admin_already_exists() {
    stubTransaction();
    User existing = existingUser(Role.USER);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("USER");

    assertThat(existing.getRole()).isEqualTo(Role.USER);
    verify(users, never()).saveAndFlush(any(User.class));
    verify(users, never()).existsByRole(any(Role.class));
  }

  @Test
  void bootstrap_uses_independent_read_committed_transactions() {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.bootstrap(EMAIL, PASSWORD);

    ArgumentCaptor<TransactionDefinition> definition =
        ArgumentCaptor.forClass(TransactionDefinition.class);
    verify(transactionManager).getTransaction(definition.capture());
    assertThat(definition.getValue().getPropagationBehavior())
        .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    assertThat(definition.getValue().getIsolationLevel())
        .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
  }

  @Test
  void created_log_is_not_emitted_when_commit_fails(CapturedOutput output) {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    doThrow(new TransactionSystemException("commit failed"))
        .when(transactionManager)
        .commit(any());

    assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD))
        .isInstanceOf(TransactionSystemException.class);

    assertThat(output.getAll()).doesNotContain("Admin bootstrap created");
  }

  @Test
  @ResourceLock(Resources.LOCALE)
  void bootstrap_registration_and_login_normalize_email_with_root_locale() {
    Locale previous = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));
      stubTransaction();
      when(users.findByEmail("admin@example.com")).thenReturn(Optional.empty());
      when(users.existsByRole(Role.ADMIN)).thenReturn(false);
      when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

      service.bootstrap(" ADMIN@EXAMPLE.COM ", PASSWORD);

      ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
      verify(users).saveAndFlush(created.capture());
      User admin = created.getValue();
      assertThat(admin.getEmail()).isEqualTo("admin@example.com");
      assertThat(new User("ADMIN@EXAMPLE.COM", "hash", Role.USER).getEmail())
          .isEqualTo("admin@example.com");

      JwtService jwt = mock(JwtService.class);
      AuthService auth = new AuthService(users, passwords, jwt);
      when(users.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
      when(jwt.generateToken(any(User.class))).thenReturn("token");

      assertThat(auth.login(new LoginRequest(" ADMIN@EXAMPLE.COM ", PASSWORD)).email())
          .isEqualTo("admin@example.com");
      assertThat(
              auth.register(new RegisterRequest(" NEWADMIN@EXAMPLE.COM ", "password123")).email())
          .isEqualTo("newadmin@example.com");
    } finally {
      Locale.setDefault(previous);
    }
  }

  @Test
  void duplicate_insert_after_another_admin_wins_is_a_safe_noop() {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false, true);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    service.bootstrap(EMAIL, PASSWORD);

    verify(users).saveAndFlush(any(User.class));
    verify(jdbc, atLeast(2)).execute(contains("pg_advisory_xact_lock"));
    verify(transactionManager).rollback(any());
    verify(transactionManager).commit(any());
  }

  @Test
  void duplicate_insert_after_the_email_becomes_a_user_fails_closed() {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false);
    User existing = existingUser(Role.USER);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty(), Optional.of(existing));
    when(users.saveAndFlush(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("duplicate"));

    assertThatThrownBy(() -> service.bootstrap(EMAIL, PASSWORD))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("USER")
        .hasMessageNotContaining(PASSWORD);

    assertThat(existing.getRole()).isEqualTo(Role.USER);
    assertThat(passwords.matches(OLD_PASSWORD, existing.getPasswordHash())).isTrue();
    verify(users).saveAndFlush(any(User.class));
    verify(transactionManager, atLeast(2)).rollback(any());
  }

  @Test
  void password_and_hash_are_not_logged(CapturedOutput output) {
    stubTransaction();
    when(users.existsByRole(Role.ADMIN)).thenReturn(false);
    when(users.findByEmail(EMAIL)).thenReturn(Optional.empty());
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    service.bootstrap(EMAIL, PASSWORD);

    ArgumentCaptor<User> created = ArgumentCaptor.forClass(User.class);
    verify(users).saveAndFlush(created.capture());
    String hash = created.getValue().getPasswordHash();

    assertThat(output.getAll()).contains("Admin bootstrap created the initial ADMIN account");
    assertThat(output.getAll()).doesNotContain(PASSWORD).doesNotContain(hash);
  }

  @Test
  void runner_opens_gate_only_after_successful_bootstrap() {
    AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
    AdminBootstrapGate gate = new AdminBootstrapGate();
    doAnswer(
            invocation -> {
              assertThat(gate.isOpen()).isFalse();
              return null;
            })
        .when(bootstrap)
        .bootstrap(EMAIL, PASSWORD);

    new AdminBootstrapRunner(bootstrap, gate, EMAIL, PASSWORD).run(null);

    verify(bootstrap).bootstrap(EMAIL, PASSWORD);
    assertThat(gate.isOpen()).isTrue();
  }

  @Test
  void runner_leaves_gate_closed_when_bootstrap_fails() {
    AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
    AdminBootstrapGate gate = new AdminBootstrapGate();
    doThrow(new IllegalStateException("bootstrap failed"))
        .when(bootstrap)
        .bootstrap(EMAIL, PASSWORD);

    assertThatThrownBy(() -> new AdminBootstrapRunner(bootstrap, gate, EMAIL, PASSWORD).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bootstrap failed");

    assertThat(gate.isOpen()).isFalse();
  }

  @Test
  void runner_leaves_gate_closed_when_the_advisory_lock_times_out() {
    AdminBootstrapService bootstrap = mock(AdminBootstrapService.class);
    AdminBootstrapGate gate = new AdminBootstrapGate();
    doThrow(new CannotAcquireLockException("advisory lock timeout"))
        .when(bootstrap)
        .bootstrap(EMAIL, PASSWORD);

    assertThatThrownBy(() -> new AdminBootstrapRunner(bootstrap, gate, EMAIL, PASSWORD).run(null))
        .isInstanceOf(CannotAcquireLockException.class)
        .hasMessageContaining("advisory lock timeout");

    assertThat(gate.isOpen()).isFalse();
  }
}
