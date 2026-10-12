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

  public AuthService(UserRepository users, PasswordEncoder passwords, JwtService jwt) {
    this.users = users;
    this.passwords = passwords;
    this.jwt = jwt;
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
    User user =
        users
            .findByEmail(request.email().toLowerCase())
            .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
    if (!passwords.matches(request.password(), user.getPasswordHash())) {
      throw new BadCredentialsException("Invalid credentials");
    }
    return new AuthResponse(jwt.generateToken(user), user.getEmail(), user.getRole().name());
  }
}
