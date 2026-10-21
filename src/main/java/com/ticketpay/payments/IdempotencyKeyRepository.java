package com.ticketpay.payments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {

  Optional<IdempotencyKey> findByUserIdAndKey(UUID userId, String key);

  /**
   * Retention cleanup. Only removes keys whose own expiry has passed AND whose
   * payment reached a terminal SUCCEEDED/FAILED/REFUNDED state (or orphan keys with
   * no payment at all, e.g. a crash between key insert and payment insert). Keys of
   * unresolved payments are never deleted: an expired key over an unresolved payment
   * still blocks new charges.
   */
  @Modifying
  @Query(
      "delete from IdempotencyKey k where k.expiresAt < :now"
          + " and (k.paymentId is null or k.paymentId in"
          + " (select p.id from Payment p where p.status in :terminal))")
  int deleteExpiredTerminal(List<PaymentStatus> terminal, Instant now);
}
