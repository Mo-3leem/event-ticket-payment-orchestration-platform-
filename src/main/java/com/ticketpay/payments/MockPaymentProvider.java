package com.ticketpay.payments;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
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

  public enum RefundBehavior {
    SUCCESS,
    DECLINED,
    /** Sleeps past the orchestrator timeout without recording the refund. */
    SLEEP_NO_RECORD,
    /** Records the refund, then sleeps past the orchestrator timeout (lost response). */
    RECORD_THEN_SLEEP
  }

  private final ConcurrentHashMap<String, ChargeResult> store = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> refLocks = new ConcurrentHashMap<>();
  private final AtomicInteger processedCount = new AtomicInteger();

  private final ConcurrentHashMap<String, ChargeResult> refundStore = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> refundLocks = new ConcurrentHashMap<>();
  private final AtomicInteger refundProcessedCount = new AtomicInteger();

  private volatile MockBehavior behavior = MockBehavior.SUCCESS;
  private volatile RefundBehavior refundBehavior = RefundBehavior.SUCCESS;
  private volatile long sleepMs = 0;
  private volatile boolean lookupThrows = false;
  private volatile boolean refundLookupThrows = false;
  private volatile CountDownLatch refundGate;
  private final AtomicInteger refundParked = new AtomicInteger();

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

  /**
   * Same contract as {@link #charge}: deduplicates by refund reference, so a
   * same-reference re-drive returns the originally recorded outcome instead of
   * refunding twice. Scriptable exactly like charges (outcome/timeout/lazy-record).
   */
  @Override
  public ChargeResult refund(
      String refundReference, String chargeReference, long amountMinor, String currency) {
    // Test gate (setRefundGate): park BEFORE the per-reference monitor so that
    // concurrent callers are held outside execution until the test releases them,
    // making provider-level races deterministic instead of timing-dependent.
    CountDownLatch gate = refundGate;
    if (gate != null) {
      refundParked.incrementAndGet();
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ProviderCallAbortedException("Mock refund call aborted while gated");
      } finally {
        refundParked.decrementAndGet();
      }
    }
    Object lock = refundLocks.computeIfAbsent(refundReference, k -> new Object());
    synchronized (lock) {
      ChargeResult existing = refundStore.get(refundReference);
      if (existing != null) {
        return existing;
      }
      switch (refundBehavior) {
        case SUCCESS -> {
          return recordRefund(refundReference, ChargeResult.ok());
        }
        case DECLINED -> {
          return recordRefund(refundReference, ChargeResult.declined("REFUND_REJECTED"));
        }
        case SLEEP_NO_RECORD -> {
          sleep();
          return ChargeResult.declined("REFUND_TIMEOUT_SIMULATED");
        }
        case RECORD_THEN_SLEEP -> {
          ChargeResult result = recordRefund(refundReference, ChargeResult.ok());
          sleep();
          return result;
        }
        default -> throw new IllegalStateException("Unknown mock refund behavior");
      }
    }
  }

  @Override
  public Optional<ChargeResult> lookupRefund(String refundReference) {
    if (refundLookupThrows) {
      throw new ProviderStatusUnknownException("Mock refund lookup is ambiguous by script");
    }
    return Optional.ofNullable(refundStore.get(refundReference));
  }

  private ChargeResult recordRefund(String refundReference, ChargeResult result) {
    refundStore.put(refundReference, result);
    refundProcessedCount.incrementAndGet();
    return result;
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

  public void setRefundBehavior(RefundBehavior refundBehavior) {
    this.refundBehavior = refundBehavior;
  }

  public void setRefundLookupThrows(boolean refundLookupThrows) {
    this.refundLookupThrows = refundLookupThrows;
  }

  /**
   * Test hook: when set, every refund() call parks on this latch before executing.
   * {@link #getRefundParkedCount()} shows how many calls are parked, so a test can
   * release them only once all racers have arrived (deterministic, not sleep-based).
   */
  public void setRefundGate(CountDownLatch refundGate) {
    this.refundGate = refundGate;
  }

  public int getRefundParkedCount() {
    return refundParked.get();
  }

  /** Pre-records an outcome without counting it as processing (crash simulation). */
  public void rehearse(String providerReference, ChargeResult result) {
    store.putIfAbsent(providerReference, result);
  }

  /** Pre-records a refund outcome without counting it as processing. */
  public void rehearseRefund(String refundReference, ChargeResult result) {
    refundStore.putIfAbsent(refundReference, result);
  }

  public int getProcessedCount() {
    return processedCount.get();
  }

  /** Number of refund operations actually executed (deduplicated). */
  public int getRefundProcessedCount() {
    return refundProcessedCount.get();
  }

  public boolean hasRecord(String providerReference) {
    return store.containsKey(providerReference);
  }

  public boolean hasRefundRecord(String refundReference) {
    return refundStore.containsKey(refundReference);
  }

  public void reset() {
    store.clear();
    refLocks.clear();
    processedCount.set(0);
    refundStore.clear();
    refundLocks.clear();
    refundProcessedCount.set(0);
    behavior = MockBehavior.SUCCESS;
    refundBehavior = RefundBehavior.SUCCESS;
    sleepMs = 0;
    lookupThrows = false;
    refundLookupThrows = false;
    refundGate = null;
    refundParked.set(0);
  }
}
