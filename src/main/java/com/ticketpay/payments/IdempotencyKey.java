package com.ticketpay.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
    name = "idempotency_keys",
    uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "idem_key"}))
public class IdempotencyKey {

  @Id
  @Column(name = "id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false, updatable = false)
  private UUID userId;

  @Column(name = "idem_key", nullable = false, updatable = false, length = 64)
  private String key;

  @Column(name = "payment_id")
  private UUID paymentId;

  @Column(name = "request_hash", nullable = false, updatable = false, length = 64)
  private String requestHash;

  @Column(name = "response_code")
  private Integer responseCode;

  @Column(name = "response_body", columnDefinition = "TEXT")
  private String responseBody;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private KeyStatus status;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected IdempotencyKey() {}

  public IdempotencyKey(UUID userId, String key, String requestHash, Instant expiresAt) {
    this.id = UUID.randomUUID();
    this.userId = userId;
    this.key = key;
    this.requestHash = requestHash;
    this.status = KeyStatus.IN_PROGRESS;
    this.expiresAt = expiresAt;
    this.createdAt = Instant.now();
    this.updatedAt = this.createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getKey() {
    return key;
  }

  public UUID getPaymentId() {
    return paymentId;
  }

  public String getRequestHash() {
    return requestHash;
  }

  public Integer getResponseCode() {
    return responseCode;
  }

  public String getResponseBody() {
    return responseBody;
  }

  public KeyStatus getStatus() {
    return status;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public void linkPayment(UUID paymentId) {
    this.paymentId = paymentId;
    this.updatedAt = Instant.now();
  }

  public void complete(int responseCode, String responseBody) {
    this.responseCode = responseCode;
    this.responseBody = responseBody;
    this.status = KeyStatus.COMPLETED;
    this.updatedAt = Instant.now();
  }
}
