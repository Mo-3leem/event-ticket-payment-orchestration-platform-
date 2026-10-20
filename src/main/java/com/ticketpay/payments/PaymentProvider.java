package com.ticketpay.payments;

import java.util.Optional;

/**
 * Abstraction over an external payment provider. The orchestrator depends only on
 * this interface — never on any provider SDK. Day 3 ships a single in-process mock;
 * a real provider later implements the same contract, including idempotent charge
 * semantics keyed by the stable provider reference and a status lookup for recovery.
 */
public interface PaymentProvider {

  String name();

  /**
   * Attempts a charge exactly as the provider sees it. Implementations must deduplicate
   * by providerReference: repeated calls with the same reference must not process
   * the payment twice.
   */
  ChargeResult charge(String providerReference, long amountMinor, String currency);

  /**
   * Recovery lookup. Empty means the provider has no record (safe to drive);
   * throwing {@link ProviderStatusUnknownException} means the outcome cannot be
   * determined (must stay UNKNOWN).
   */
  Optional<ChargeResult> lookup(String providerReference);

  /**
   * Refunds a captured charge. Implementations MUST deduplicate by
   * {@code refundReference}: repeated calls with the same reference must not
   * process the refund twice and must return the originally recorded outcome.
   * This is the same idempotency contract as {@link #charge}, applied to refunds —
   * it is what makes same-reference re-drives after crashes safe.
   *
   * <p>Known limit: the in-process mock demonstrates this plumbing only. A real
   * provider's adherence to the contract must be verified against that provider's
   * own idempotency documentation before relying on it in production.
   */
  ChargeResult refund(
      String refundReference, String chargeReference, long amountMinor, String currency);

  /**
   * Refund outcome lookup, independent of {@link #lookup(String)} (which resolves
   * the CHARGE only and cannot see refunds). Semantics are deliberately identical
   * to {@code lookup}, keyed by {@code refundReference}:
   *
   * <ul>
   *   <li>present + success — the refund is definitively complete;</li>
   *   <li>present + declined — the refund is definitively declined;</li>
   *   <li>empty — the provider has NO record of this refund: nothing was refunded,
   *       so a re-drive with the SAME reference is safe (the provider deduplicates
   *       by that reference);</li>
   *   <li>{@link ProviderStatusUnknownException} — indeterminate: keep the refund
   *       UNKNOWN and never re-drive.
   * </ul>
   */
  Optional<ChargeResult> lookupRefund(String refundReference);

  record ChargeResult(boolean success, String declineCode) {
    public static ChargeResult ok() {
      return new ChargeResult(true, null);
    }

    public static ChargeResult declined(String declineCode) {
      return new ChargeResult(false, declineCode);
    }
  }
}
