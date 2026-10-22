package com.ticketpay.identity;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * Starts closed so no HTTP request can reach an application handler before the
 * one-time administrator bootstrap has completed.
 */
@Component
public class AdminBootstrapGate {

  private final AtomicBoolean open = new AtomicBoolean(false);

  public boolean isOpen() {
    return open.get();
  }

  public void open() {
    open.set(true);
  }
}
