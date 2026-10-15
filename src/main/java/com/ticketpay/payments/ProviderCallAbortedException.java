package com.ticketpay.payments;

/** Aborts a scripted mock call (e.g. orchestrator timeout won the race). Test-flow only. */
public class ProviderCallAbortedException extends RuntimeException {
  public ProviderCallAbortedException(String message) {
    super(message);
  }
}
