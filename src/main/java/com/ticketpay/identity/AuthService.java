package com.ticketpay.identity;

import com.ticketpay.identity.dto.AuthResponse;
import com.ticketpay.identity.dto.LoginRequest;
import com.ticketpay.identity.dto.RegisterRequest;
import com.ticketpay.shared.error.ConflictException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

  private final UserRepository users;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  /**
   * Compared against when the email is unknown, so login latency does not reveal
   * whether an account exists (BCrypt cost is paid either way).
   */
  private final String dummyHash;

  public AuthService(UserRepository users, PasswordEncoder passwords, JwtService jwt) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
    this.dummyHash = passwords.encode(java.util.UUID.randomUUID().toString());
  }

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    String email = request.email().toLowerCase();
    if (users.existsByEmail(email)) {
      throw new ConflictException("Email already registered");
    }
    User user = new User(email, passwords.encode(request.password()), Role.USER);
    users.save(user);
    return new AuthResponse(jwt.generateToken(user), user.getEmail(), user.getRole().name());
  }

  @Transactional(readOnly = true)
  public AuthResponse login(LoginRequest request) {
    User user = users.findByEmail(request.email().toLowerCase()).orElse(null);
    String hash = user == null ? dummyHash : user.getPasswordHash();
    boolean matches = passwords.matches(request.password(), hash);
    if (user == null || !matches) {
      throw new BadCredentialsException("Invalid credentials");
    }
    return new AuthResponse(jwt.generateToken(user), user.getEmail(), user.getRole().name());
  }
}
