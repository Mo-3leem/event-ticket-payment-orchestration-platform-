package com.ticketpay.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Retention cleanup for idempotency records. Only keys past their expiry whose
 * payment reached SUCCEEDED/FAILED/REFUNDED (or orphan keys with no payment) are
 * removed — keys of unresolved payments are retained so expiry can never
 * authorize a new charge.
 */
@Component
public class PaymentCleanupJob {

  private static final Logger log = LoggerFactory.getLogger(PaymentCleanupJob.class);

  private final PaymentService payments;

  public PaymentCleanupJob(PaymentService payments) {
    this.payments = payments;
  }

  @Scheduled(fixedDelayString = "${app.payment.cleanup-interval-ms:3600000}")
  public void purge() {
    int removed = payments.purgeExpiredKeys();
    if (removed > 0) {
      log.info("Purged {} expired idempotency keys", removed);
    }
  }
}
