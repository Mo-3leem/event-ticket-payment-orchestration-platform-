package com.ticketpay.payments;

public enum ReconciliationVerdict {
  PROVIDER_SUCCESS,
  PROVIDER_SUCCESS_BOOKING_EXPIRED,
  PROVIDER_DECLINED,
  MANUAL_REVIEW,
  REFUND_COMPLETED
}
