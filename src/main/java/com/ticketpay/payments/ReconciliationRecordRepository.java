package com.ticketpay.payments;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReconciliationRecordRepository extends JpaRepository<ReconciliationRecord, UUID> {

  Page<ReconciliationRecord> findByPaymentIdOrderByCreatedAtDesc(UUID paymentId, Pageable pageable);

  long countByPaymentId(UUID paymentId);
}
