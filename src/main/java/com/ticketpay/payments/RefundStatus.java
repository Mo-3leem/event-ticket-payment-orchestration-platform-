package com.ticketpay.payments;

/**
 * Refund lifecycle. REQUESTED is the durable intent persisted before any provider
 * I/O. SUCCEEDED/FAILED require a definitive provider outcome; UNKNOWN means the
 * result could not be determined and is resolved only by provider lookup — never
 * by blindly re-driving an ambiguous refund.
 */
public enum RefundStatus {
  REQUESTED,
  SUCCEEDED,
  FAILED,
  UNKNOWN
}
