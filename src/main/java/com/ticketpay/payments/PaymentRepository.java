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

  Page<Payment> findByUserIdOrderByCreatedAtDescIdDesc(UUID userId, Pageable pageable);

  Page<Payment> findByUserIdAndStatusOrderByCreatedAtDescIdDesc(
      UUID userId, PaymentStatus status, Pageable pageable);

  Page<Payment> findByStatusOrderByCreatedAtDescIdDesc(PaymentStatus status, Pageable pageable);

  Page<Payment> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

  /**
   * Aged UNKNOWN payments due for reconciliation. SKIP LOCKED lets concurrent workers
   * (or a live payment completion holding the row) skip rather than block; each
   * skipped row is picked up on a later cycle.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select p from Payment p where p.status = :status"
          + " and coalesce(p.lastReconciledAt, p.createdAt) < :cutoff"
          + " order by p.createdAt asc, p.id asc")
  List<Payment> findReconciliable(
      PaymentStatus status, Instant cutoff, Pageable pageable);

  /**
   * Crash-left AUTHORIZING rows older than the cutoff. SKIP LOCKED keeps a live
   * transaction that is finalizing the same row out of this batch.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select p from Payment p where p.status = :status"
          + " and p.createdAt < :cutoff"
          + " and (p.lastReconciledAt is null or p.lastReconciledAt < :cutoff)"
          + " order by p.createdAt asc, p.id asc")
  List<Payment> findStaleAuthorizing(
      PaymentStatus status, Instant cutoff, Pageable pageable);

  /**
   * Refund-required flag: captured payments whose booking expired at confirmation
   * (BOOKING_EXPIRED_AT_CONFIRM note) and which have no refund attempt yet. Derived
   * only — refunds start exclusively from the explicit Admin endpoint. FAILED
   * refunds are terminal (no retry-after-FAILED in v1) so they never reappear.
   * Deterministic ordering for stable pagination.
   */
  @Query(
      "select p from Payment p where p.status = :status"
          + " and p.note = 'BOOKING_EXPIRED_AT_CONFIRM'"
          + " and not exists (select r from Refund r where r.payment.id = p.id)"
          + " order by p.createdAt desc, p.id desc")
  Page<Payment> findRefundRequired(PaymentStatus status, Pageable pageable);
}
