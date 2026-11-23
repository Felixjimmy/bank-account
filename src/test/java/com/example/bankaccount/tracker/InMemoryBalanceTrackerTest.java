package com.example.bankaccount.tracker;

import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.Transaction;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class InMemoryBalanceTrackerTest {

  /** Checks the 999/1,000/1,001 boundary: only a complete group is released, in acceptance order. */
  @Test
  void onlyCompleteGroupsAreExposedInAcceptanceOrder() {
    var tracker = new InMemoryBalanceTracker(1);
    var expected = new java.util.ArrayList<Transaction>();
    for (int i = 0; i < 999; i++) {
      var transaction = credit();
      expected.add(transaction);
      tracker.processTransaction(transaction);
    }
    assertTrue(tracker.pollCompletedGroup().isEmpty());
    var closing = credit();
    expected.add(closing);
    tracker.processTransaction(closing);
    tracker.processTransaction(credit());
    assertEquals(expected, tracker.pollCompletedGroup().orElseThrow());
    assertTrue(tracker.pollCompletedGroup().isEmpty());
    assertEquals(new BigDecimal("200200.00"), tracker.exactBalance());
  }

  /** Interrupts a producer blocked by a full queue and verifies its transaction was never accepted. */
  @Test
  void interruptedWaitPreservesBalanceAndInterruptStatus() throws Exception {
    var tracker = new InMemoryBalanceTracker(1);
    for (int i = 0; i < 1999; i++) {
      tracker.processTransaction(credit());
    }
    var interrupted = new CompletableFuture<Boolean>();
    var worker = new Thread(() -> {
      try {
        tracker.processTransaction(credit());
        interrupted.complete(false);
      } catch (IllegalStateException failure) {
        interrupted.complete(Thread.currentThread().isInterrupted());
      }
    });
    worker.start();
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
        while (worker.getState() != Thread.State.WAITING) {
          Thread.sleep(1);
        }
      });
      worker.interrupt();
      assertTrue(interrupted.get(2, TimeUnit.SECONDS));
      assertEquals(new BigDecimal("399800.00"), tracker.exactBalance());
    } finally {
      tracker.stopAccepting();
      worker.join(2000);
    }
  }

  /** Checks zero balance, exact penny arithmetic, negative balances and no balance change after null input. */
  @Test
  void exactArithmeticSupportsNegativeBalancesAndRejectedInputs() {
    var tracker = new InMemoryBalanceTracker(1);
    assertEquals(new BigDecimal("0.00"), tracker.exactBalance());
    tracker.processTransaction(
      new Transaction(UUID.randomUUID(), new BigDecimal("200.10"))
    );
    tracker.processTransaction(
      new Transaction(UUID.randomUUID(), new BigDecimal("-300.25"))
    );
    assertEquals(new BigDecimal("-100.15"), tracker.exactBalance());
    assertEquals(-100.15, tracker.retrieveBalance());
    assertThrows(NullPointerException.class, () ->
      tracker.processTransaction(null)
    );
    assertEquals(new BigDecimal("-100.15"), tracker.exactBalance());
  }

  /** Runs credits and debits concurrently and reconciles the balance with all 2,000 retained unique transactions. */
  @Test
  void concurrentWritersReconcileWithRetainedTransactions() throws Exception {
    var tracker = new InMemoryBalanceTracker(2);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var credits = executor.submit(() -> {
        for (int i = 0; i < 1000; i++) tracker.processTransaction(credit());
      });
      var debits = executor.submit(() -> {
        for (int i = 0; i < 1000; i++) {
          tracker.processTransaction(
            new Transaction(UUID.randomUUID(), new BigDecimal("-199.99"))
          );
        }
      });
      credits.get(5, TimeUnit.SECONDS);
      debits.get(5, TimeUnit.SECONDS);
    }
    assertEquals(new BigDecimal("10.00"), tracker.exactBalance());
    var retained = new java.util.ArrayList<Transaction>();
    retained.addAll(tracker.pollCompletedGroup().orElseThrow());
    retained.addAll(tracker.pollCompletedGroup().orElseThrow());
    assertEquals(
      2000,
      retained.stream().map(Transaction::id).distinct().count()
    );
    assertEquals(
      tracker.exactBalance(),
      retained
        .stream()
        .map(Transaction::amount)
        .reduce(BigDecimal.ZERO, BigDecimal::add)
    );
  }

  /** Checks shutdown wakes a queue-blocked producer and rejects its pending transaction without changing balance. */
  @Test
  void shutdownReleasesBlockedProducerWithoutAcceptingItsTransaction() throws Exception {
    var tracker = new InMemoryBalanceTracker(1);
    for (int i = 0; i < 1999; i++) tracker.processTransaction(credit());
    var result = new CompletableFuture<Throwable>();
    var worker = new Thread(() -> {
      try {
        tracker.processTransaction(credit());
        result.complete(null);
      } catch (Exception failure) {
        result.complete(failure);
      }
    });
    worker.start();
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
        while (worker.getState() != Thread.State.WAITING) Thread.sleep(1);
      });
      tracker.stopAccepting();
      assertInstanceOf(
        IllegalStateException.class,
        result.get(2, TimeUnit.SECONDS)
      );
      assertEquals(new BigDecimal("399800.00"), tracker.exactBalance());
    } finally {
      tracker.stopAccepting();
      worker.join(2000);
    }
  }

  private static Transaction credit() {
    return new Transaction(UUID.randomUUID(), new BigDecimal("200.00"));
  }

  /** Loads real YAML defaults and checks valid, nonpositive and nonnumeric queue-capacity overrides. */
  @Test
  void configurationLoadsFromYamlAndRejectsInvalidOverrides() {
    var context = new ApplicationContextRunner()
      .withInitializer(new ConfigDataApplicationContextInitializer())
      .withUserConfiguration(InMemoryBalanceTracker.class);
    context.run(app -> assertNull(app.getStartupFailure()));
    for (String invalid : new String[] { "0", "-1", "abc" }) {
      context
        .withPropertyValues(
          "bank-account.tracker.max-queued-audit-groups=" + invalid
        )
        .run(app -> assertNotNull(app.getStartupFailure()));
    }
    context
      .withPropertyValues("bank-account.tracker.max-queued-audit-groups=1")
      .run(app -> assertNull(app.getStartupFailure()));
  }

  /** Checks backpressure keeps balance reads available, then resumes acceptance when an immutable group is collected. */
  @Test
  void fullQueueWaitsWithoutChangingBalanceAndResumesAfterCollection() throws Exception {
    var tracker = new InMemoryBalanceTracker(1);
    for (int i = 0; i < 1999; i++) {
      tracker.processTransaction(credit());
    }
    var result = new CompletableFuture<Void>();
    Thread producer = new Thread(() -> {
      try {
        tracker.processTransaction(credit());
        result.complete(null);
      } catch (Throwable failure) {
        result.completeExceptionally(failure);
      }
    });
    producer.start();
    try {
      assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
        while (producer.getState() != Thread.State.WAITING) {
          Thread.sleep(1);
        }
      });
      assertFalse(result.isDone());
      assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
        assertEquals(new BigDecimal("399800.00"), tracker.exactBalance())
      );
      var first = tracker.pollCompletedGroup().orElseThrow();
      assertEquals(1000, first.size());
      assertThrows(UnsupportedOperationException.class, () ->
        first.add(credit())
      );
      result.get(2, TimeUnit.SECONDS);
      var second = tracker.pollCompletedGroup().orElseThrow();
      assertEquals(1000, second.size());
      assertTrue(java.util.Collections.disjoint(first, second));
      assertTrue(tracker.pollCompletedGroup().isEmpty());
      assertEquals(new BigDecimal("400000.00"), tracker.exactBalance());
    } finally {
      tracker.stopAccepting();
      producer.join(2000);
      assertFalse(producer.isAlive());
    }
  }
}
