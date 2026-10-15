package com.ticketpay.payments;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentAttemptRepository extends JpaRepository<PaymentAttempt, UUID> {

  long countByPaymentId(UUID paymentId);
}
