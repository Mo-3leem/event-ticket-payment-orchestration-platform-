package com.ticketpay.payments;

/** The provider cannot determine the outcome — the payment must stay UNKNOWN. */
public class ProviderStatusUnknownException extends RuntimeException {
  public ProviderStatusUnknownException(String message) {
    super(message);
  }
}
