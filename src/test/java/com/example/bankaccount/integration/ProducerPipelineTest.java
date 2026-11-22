package com.example.bankaccount.integration;

import com.example.bankaccount.audit.AuditWorker;
import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.producer.TransactionGenerator;
import com.example.bankaccount.producer.TransactionProducer;
import com.example.bankaccount.service.BankAccountService;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.Tag("integration")
class ProducerPipelineTest {

  @Test
  void producerAndTrackerReconcileAcceptedTransactions() throws Exception {
    exercisePipeline(false);
  }

  @Test
  void producerTrackerAndAuditPublishTheAcceptedGroup() throws Exception {
    exercisePipeline(true);
  }

  private void exercisePipeline(boolean auditEnabled) throws Exception {
    var tracker = new InMemoryBalanceTracker(2);
    var accepted = new ArrayList<Transaction>();
    var timestamps = new ArrayList<Long>();
    BankAccountService recordingAccount = new BankAccountService() {
      public synchronized void processTransaction(Transaction transaction) {
        tracker.processTransaction(transaction);
        accepted.add(transaction);
        timestamps.add(System.nanoTime());
      }

      public double retrieveBalance() {
        return tracker.retrieveBalance();
      }
    };
    // A reproducible uniform sample over the full producer amount range.
    var random = new Random(317);
    var generator = new TransactionGenerator(
      () -> BigDecimal.valueOf(random.nextInt(20_000, 50_000_001), 2),
      UUID::randomUUID
    );
    var submissions = new CopyOnWriteArrayList<AuditResult>();
    var worker = auditEnabled
      ? new AuditWorker(tracker, submissions::add)
      : null;
    var producer = new TransactionProducer(recordingAccount, generator);
    if (worker != null) worker.start();
    long windowStart;
    long windowEnd;
    try {
      producer.start();
      Thread.sleep(2000);
      windowStart = System.nanoTime();
      Thread.sleep(20_000);
      windowEnd = System.nanoTime();
    } finally {
      // Stop acceptance first so even a blocked producer can terminate.
      tracker.stopAccepting();
      producer.close();
      if (worker != null) worker.close();
    }

    assertFalse(producer.isRunning());
    assertTrue(
      accepted.size() >= 1000,
      "Expected a complete group within 22 seconds"
    );
    assertTrue(
      accepted.size() < 2000,
      "Expected one complete group and a partial group"
    );
    long credits = accepted
      .stream()
      .filter(t -> t.amount().signum() > 0)
      .count();
    assertEquals(credits, producer.creditsProduced());
    assertEquals(accepted.size() - credits, producer.debitsProduced());
    assertEquals(
      accepted.size(),
      accepted.stream().map(Transaction::id).distinct().count()
    );
    BigDecimal expectedBalance = accepted
      .stream()
      .map(Transaction::amount)
      .reduce(new BigDecimal("0.00"), BigDecimal::add);
    assertEquals(expectedBalance, tracker.exactBalance());
    assertEquals(expectedBalance.doubleValue(), tracker.retrieveBalance());

    List<Transaction> completed;
    if (auditEnabled) {
      assertEquals(1, submissions.size());
      var result = submissions.getFirst();
      completed = result
        .batches()
        .stream()
        .flatMap(b -> b.transactions().stream())
        .toList();
      for (var batch : result.batches()) {
        var absoluteTotal = batch
          .transactions()
          .stream()
          .map(t -> t.amount().abs())
          .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(absoluteTotal, batch.totalValueOfAllTransactions());
        assertTrue(absoluteTotal.compareTo(new BigDecimal("1000000.00")) <= 0);
      }
    } else {
      completed = tracker.pollCompletedGroup().orElseThrow();
      assertEquals(accepted.subList(0, 1000), completed);
    }
    assertEquals(1000, completed.size());
    assertEquals(
      new HashSet<>(accepted.subList(0, 1000)),
      new HashSet<>(completed)
    );
    assertTrue(tracker.pollCompletedGroup().isEmpty());
    assertTrue(
      Collections.disjoint(accepted.subList(1000, accepted.size()), completed)
    );

    long measuredCredits = 0;
    long measuredDebits = 0;
    for (int i = 0; i < accepted.size(); i++) {
      long time = timestamps.get(i);
      if (time >= windowStart && time < windowEnd) {
        if (accepted.get(i).amount().signum() > 0) measuredCredits++;
        else measuredDebits++;
      }
    }
    double seconds =
      (windowEnd - windowStart) / (double) TimeUnit.SECONDS.toNanos(1);
    System.out.printf(
      Locale.ROOT,
      "THROUGHPUT audit=%s seconds=%.6f credits=%d debits=%d creditRate=%.3f debitRate=%.3f totalRate=%.3f%n",
      auditEnabled,
      seconds,
      measuredCredits,
      measuredDebits,
      measuredCredits / seconds,
      measuredDebits / seconds,
      (measuredCredits + measuredDebits) / seconds
    );
  }
}
