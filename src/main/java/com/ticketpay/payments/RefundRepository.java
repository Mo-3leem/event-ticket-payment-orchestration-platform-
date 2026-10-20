package com.ticketpay.payments;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

  Optional<Refund> findByPaymentId(UUID paymentId);

  List<Refund> findByPaymentIdOrderByCreatedAtDescIdDesc(UUID paymentId);
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from Refund r where r.id = :id")
  Optional<Refund> findByIdForUpdate(UUID id);

  /**
   * Aged in-flight refunds (REQUESTED/UNKNOWN) due for reconciliation. SKIP LOCKED
   * lets concurrent workers or a live finalization holding the row skip rather than
   * block; each skipped row is picked up on a later cycle. updated_at is refreshed
   * by every check that resolves nothing, so empty/ambiguous lookups are not
   * hammered continuously.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      "select r from Refund r where r.status in :statuses"
          + " and r.updatedAt < :cutoff"
          + " order by r.updatedAt asc")
  List<Refund> findReconciliable(List<RefundStatus> statuses, Instant cutoff, Pageable pageable);
}
