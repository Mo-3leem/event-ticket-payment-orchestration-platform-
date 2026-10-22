package com.ticketpay.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "email", nullable = false, unique = true)
  private String email;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "role", nullable = false, length = 20)
  private Role role;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected User() {}

  public User(String email, String passwordHash, Role role) {
    this.id = UUID.randomUUID();
    this.email = email.trim().toLowerCase(Locale.ROOT);
    this.passwordHash = passwordHash;
    this.role = role;
    this.createdAt = Instant.now();
  }

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Role getRole() {
    return role;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
