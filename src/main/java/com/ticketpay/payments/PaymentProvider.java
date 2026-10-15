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

  record ChargeResult(boolean success, String declineCode) {
    public static ChargeResult ok() {
      return new ChargeResult(true, null);
    }

    public static ChargeResult declined(String declineCode) {
      return new ChargeResult(false, declineCode);
    }
  }
}
