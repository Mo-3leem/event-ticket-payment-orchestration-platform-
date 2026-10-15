package com.ticketpay.payments;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

/**
 * In-process scripted provider for Day 3. Models the three realities the orchestrator
 * must survive: clean success/decline, timeout with nothing recorded, timeout after
 * the charge was recorded (the lost-response case), and ambiguous lookup. Charge
 * semantics are idempotent by provider reference, like a real provider idempotency key.
 *
 * <p>Behavior is scriptable per test; {@link #reset()} restores defaults.
 */
@Service
public class MockPaymentProvider implements PaymentProvider {

  public enum MockBehavior {
    SUCCESS,
    DECLINED,
    /** Sleeps past the orchestrator timeout without recording anything. */
    SLEEP_NO_RECORD,
    /** Records success, then sleeps past the orchestrator timeout (lost response). */
    RECORD_THEN_SLEEP
  }

  private final ConcurrentHashMap<String, ChargeResult> store = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> refLocks = new ConcurrentHashMap<>();
  private final AtomicInteger processedCount = new AtomicInteger();

  private volatile MockBehavior behavior = MockBehavior.SUCCESS;
  private volatile long sleepMs = 0;
  private volatile boolean lookupThrows = false;

  @Override
  public String name() {
    return "mock";
  }

  @Override
  public ChargeResult charge(String providerReference, long amountMinor, String currency) {
    Object lock = refLocks.computeIfAbsent(providerReference, k -> new Object());
    synchronized (lock) {
      ChargeResult existing = store.get(providerReference);
      if (existing != null) {
        return existing;
      }
      switch (behavior) {
        case SUCCESS -> {
          return record(providerReference, ChargeResult.ok());
        }
        case DECLINED -> {
          return record(providerReference, ChargeResult.declined("INSUFFICIENT_FUNDS"));
        }
        case SLEEP_NO_RECORD -> {
          sleep();
          return ChargeResult.declined("TIMEOUT_SIMULATED");
        }
        case RECORD_THEN_SLEEP -> {
          ChargeResult result = record(providerReference, ChargeResult.ok());
          sleep();
          return result;
        }
        default -> throw new IllegalStateException("Unknown mock behavior: " + behavior);
      }
    }
  }

  @Override
  public Optional<ChargeResult> lookup(String providerReference) {
    if (lookupThrows) {
      throw new ProviderStatusUnknownException("Mock lookup is ambiguous by script");
    }
    return Optional.ofNullable(store.get(providerReference));
  }

  private ChargeResult record(String providerReference, ChargeResult result) {
    store.put(providerReference, result);
    processedCount.incrementAndGet();
    return result;
  }

  private void sleep() {
    try {
      TimeUnit.MILLISECONDS.sleep(sleepMs);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderCallAbortedException("Mock call aborted while sleeping");
    }
  }

  // ---- test scripting hooks ----

  public void setBehavior(MockBehavior behavior) {
    this.behavior = behavior;
  }

  public void setSleepMs(long sleepMs) {
    this.sleepMs = sleepMs;
  }

  public void setLookupThrows(boolean lookupThrows) {
    this.lookupThrows = lookupThrows;
  }

  /** Pre-records an outcome without counting it as processing (crash simulation). */
  public void rehearse(String providerReference, ChargeResult result) {
    store.putIfAbsent(providerReference, result);
  }

  public int getProcessedCount() {
    return processedCount.get();
  }

  public boolean hasRecord(String providerReference) {
    return store.containsKey(providerReference);
  }

  public void reset() {
    store.clear();
    refLocks.clear();
    processedCount.set(0);
    behavior = MockBehavior.SUCCESS;
    sleepMs = 0;
    lookupThrows = false;
  }
}
