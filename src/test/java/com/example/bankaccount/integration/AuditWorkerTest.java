package com.example.bankaccount.integration;

import com.example.bankaccount.audit.AuditWorker;
import com.example.bankaccount.audit.AuditPacker;
import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("integration")
class AuditWorkerTest {

  @Test
  void rejectsNullConstructorDependenciesAndNonpositiveShutdownTimeout() {
    var tracker = new InMemoryBalanceTracker(1);
    assertThrows(NullPointerException.class, () ->
      new AuditWorker(null, new AuditPacker(), result -> {}, java.time.Duration.ofSeconds(1)));
    assertThrows(NullPointerException.class, () ->
      new AuditWorker(tracker, null, result -> {}, java.time.Duration.ofSeconds(1)));
    assertThrows(NullPointerException.class, () ->
      new AuditWorker(tracker, new AuditPacker(), null, java.time.Duration.ofSeconds(1)));
    assertThrows(IllegalArgumentException.class, () ->
      new AuditWorker(tracker, new AuditPacker(), result -> {}, java.time.Duration.ZERO));
  }

  @Test
  @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
  void timedOutGroupIsPublishedAndQueuedGroupsContinue(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
    var tracker = new InMemoryBalanceTracker(2);
    var published = new java.util.concurrent.LinkedBlockingQueue<com.example.bankaccount.domain.AuditResult>();
    var worker = new AuditWorker(tracker, published::add, java.time.Duration.ofSeconds(2), java.time.Duration.ofNanos(1));
    var expectedIds = new HashSet<UUID>();
    for (int i = 0; i < 1000; i++) {
      var id = new UUID(0, i); expectedIds.add(id);
      tracker.processTransaction(new Transaction(id, BigDecimal.valueOf(i < 500 ? 25_000_001 : -24_999_999, 2)));
    }
    var small = new Transaction(UUID.randomUUID(), new BigDecimal("200.00"));
    for (int i = 0; i < 1000; i++) tracker.processTransaction(small);
    worker.start();
    try {
      var first = published.poll(2, TimeUnit.SECONDS);
      assertNotNull(first);
      assertTrue(first.searchTimedOut());
      assertFalse(first.minimumProven());
      var retained = first.batches().stream().flatMap(b -> b.transactions().stream()).toList();
      assertEquals(1000, retained.size());
      assertEquals(expectedIds, retained.stream().map(Transaction::id).collect(java.util.stream.Collectors.toSet()));
      for (var batch : first.batches()) {
        var sum = batch.transactions().stream().map(t -> t.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(sum, batch.totalValueOfAllTransactions());
        assertTrue(sum.compareTo(new BigDecimal("1000000.00")) <= 0);
      }
      var second = published.poll(2, TimeUnit.SECONDS);
      assertNotNull(second);
      assertTrue(second.minimumProven());
      assertFalse(second.searchTimedOut());
      assertEquals(1000, second.batches().stream().mapToInt(b -> b.transactions().size()).sum());
      tracker.processTransaction(small); // Timeout did not close acceptance.
      assertEquals("queuedGroups=0, partialTransactions=1", tracker.unfinishedWork());
    } finally { worker.close(); }
    assertTrue(output.getAll().contains("minimum is NOT proven"));
    assertTrue(output.getAll().contains("minimumProven=false, searchTimedOut=true"));
    assertTrue(output.getAll().contains("minimumProven=true, searchTimedOut=false"));
  }

  @Test
  void shutdownDeadlineReportsAllOutstandingWorkEvenWhenPublisherIgnoresInterrupts() throws Exception {
    var tracker = new InMemoryBalanceTracker(2);
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var exited = new CountDownLatch(1);
    var worker = new AuditWorker(tracker, result -> {
      publishing.countDown();
      try {
        while (release.getCount() != 0) {
          try { release.await(); } catch (InterruptedException ignored) { }
        }
      } finally { exited.countDown(); }
    }, java.time.Duration.ofMillis(100));
    worker.start();
    try {
      var tx = new Transaction(UUID.randomUUID(), new BigDecimal("200.00"));
      for (int i = 0; i < 1000; i++) tracker.processTransaction(tx);
      assertTrue(publishing.await(3, TimeUnit.SECONDS));
      for (int i = 0; i < 1007; i++) tracker.processTransaction(tx);
      var failure = assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
          assertThrows(IllegalStateException.class, worker::close));
      assertTrue(failure.getMessage().contains("inFlightTransactions=1000"));
      assertTrue(failure.getMessage().contains("queuedGroups=1"));
      assertTrue(failure.getMessage().contains("partialTransactions=7"));
      assertThrows(IllegalStateException.class, () -> tracker.processTransaction(tx));
    } finally {
      release.countDown();
      assertTrue(exited.await(3, TimeUnit.SECONDS));
    }
  }

  @Test
  void stalledPublisherResumesAndDrainsCompleteGroupsInOrder() throws Exception {
    var tracker = new InMemoryBalanceTracker(1);
    var publishing = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var published = new ArrayList<List<Transaction>>();
    var worker = new AuditWorker(tracker, result -> {
      publishing.countDown();
      try {
        if (
          !release.await(5, TimeUnit.SECONDS)
        ) throw new IllegalStateException("Publisher timed out");
      } catch (InterruptedException e) {
        throw new IllegalStateException(e);
      }
      published.add(
        result
          .batches()
          .stream()
          .flatMap(b -> b.transactions().stream())
          .toList()
      );
    });
    var input = new ArrayList<Transaction>();
    for (int i = 0; i < 3001; i++) input.add(
      new Transaction(new UUID(0, i), new BigDecimal("200.00"))
    );
    worker.start();
    try {
      input.subList(0, 999).forEach(tracker::processTransaction);
      assertEquals(1, publishing.getCount());
      tracker.processTransaction(input.get(999));
      assertTrue(publishing.await(3, TimeUnit.SECONDS));
      input.subList(1000, 2999).forEach(tracker::processTransaction);
      var closing = CompletableFuture.runAsync(() ->
        tracker.processTransaction(input.get(2999))
      );
      assertThrows(TimeoutException.class, () ->
        closing.get(100, TimeUnit.MILLISECONDS)
      );
      assertEquals(new BigDecimal("599800.00"), tracker.exactBalance());
      release.countDown();
      closing.get(3, TimeUnit.SECONDS);
      tracker.processTransaction(input.get(3000));
    } finally {
      release.countDown();
      worker.close();
    }
    assertEquals(
      List.of(
        input.subList(0, 1000),
        input.subList(1000, 2000),
        input.subList(2000, 3000)
      ),
      published
    );
  }

  @Test
  void publisherFailureStopsAcceptanceAndIsReportedOnClose() {
    var tracker = new InMemoryBalanceTracker(1);
    var worker = new AuditWorker(tracker, result -> {
      throw new IllegalStateException("Output failed");
    });
    worker.start();
    var tx = new Transaction(UUID.randomUUID(), new BigDecimal("200.00"));
    for (int i = 0; i < 1000; i++) tracker.processTransaction(tx);
    assertThrows(IllegalStateException.class, worker::close);
    assertThrows(IllegalStateException.class, () ->
      tracker.processTransaction(tx)
    );
  }
}
