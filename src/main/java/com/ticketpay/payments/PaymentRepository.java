package com.ticketpay.payments;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

  Optional<Payment> findByBookingId(UUID bookingId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payment p where p.id = :id")
  Optional<Payment> findByIdForUpdate(UUID id);

  Page<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Page<Payment> findByUserIdAndStatusOrderByCreatedAtDesc(
      UUID userId, PaymentStatus status, Pageable pageable);

  Page<Payment> findByStatusOrderByCreatedAtDesc(PaymentStatus status, Pageable pageable);

  /**
   * Aged UNKNOWN payments due for reconciliation. SKIP LOCKED lets concurrent workers
   * (or a live payment completion holding the row) skip rather than block; each
   * skipped row is picked up on a later cycle.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select p from Payment p where p.status = :status"
          + " and (p.lastReconciledAt is null or p.lastReconciledAt < :cutoff)"
          + " order by p.createdAt asc")
  List<Payment> findReconciliable(
      PaymentStatus status, Instant cutoff, Pageable pageable);
}
