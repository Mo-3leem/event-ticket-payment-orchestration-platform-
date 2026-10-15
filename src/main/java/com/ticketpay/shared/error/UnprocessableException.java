package com.ticketpay.shared.error;

public class UnprocessableException extends RuntimeException {
  public UnprocessableException(String message) {
    super(message);
  }
}
