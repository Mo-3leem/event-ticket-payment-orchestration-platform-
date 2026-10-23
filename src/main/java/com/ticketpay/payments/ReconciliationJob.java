package com.ticketpay.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic reconciliation of ambiguous payments. Each cycle processes a bounded
 * batch; every payment resolves in its own transaction. Safe to overlap with live
 * payment completions and booking expiry — all outcomes are re-verified under row
 * locks. Multi-instance deployments would duplicate work (not corrupt it); fencing
 * that requires shared infrastructure is deliberately out of scope.
 */
@Component
public class ReconciliationJob {

  private static final Logger log = LoggerFactory.getLogger(ReconciliationJob.class);

  private final ReconciliationService reconciliation;
  private final RefundService refunds;

  public ReconciliationJob(ReconciliationService reconciliation, RefundService refunds) {
    this.reconciliation = reconciliation;
    this.refunds = refunds;
  }

  @Scheduled(fixedDelayString = "${app.reconciliation.interval-ms:300000}")
  public void reconcile() {
    // Crash-left AUTHORIZING rows are moved to UNKNOWN, then the normal lookup pass
    // can resolve them. Isolated so one failed pass never skips the others.
    try {
      int recovered = reconciliation.recoverStaleAuthorizingBatch();
      if (recovered > 0) {
        log.info("Moved {} stale AUTHORIZING payments to UNKNOWN", recovered);
      }
    } catch (RuntimeException failure) {
      log.error("Stale AUTHORIZING recovery cycle failed", failure);
    }
    try {
      int resolved = reconciliation.reconcileBatch();
      if (resolved > 0) {
        log.info("Reconciled {} ambiguous payments", resolved);
      }
    } catch (RuntimeException failure) {
      log.error("Charge reconciliation cycle failed", failure);
    }
    // Recovery of already-stated refund intent only (aged REQUESTED/UNKNOWN rows).
    // This never creates or executes a new refund — that requires explicit Admin action.
    try {
      int refundResolved = refunds.reconcileRefundBatch();
      if (refundResolved > 0) {
        log.info("Reconciled {} ambiguous refunds", refundResolved);
      }
    } catch (RuntimeException failure) {
      log.error("Refund reconciliation cycle failed", failure);
    }
  }
}
