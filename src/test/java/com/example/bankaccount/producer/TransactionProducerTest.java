package com.example.bankaccount.producer;

import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.service.BankAccountService;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TransactionProducerTest {

  @Test
  void dedicatedThreadsSubmitBothSignsAndStopCleanly() throws Exception {
    var seen = new CopyOnWriteArrayList<Transaction>();
    var seenByThread = new ConcurrentHashMap<
      String,
      CopyOnWriteArrayList<Transaction>
    >();
    BankAccountService account = new BankAccountService() {
      public void processTransaction(Transaction transaction) {
        seen.add(transaction);
        seenByThread
          .computeIfAbsent(Thread.currentThread().getName(), ignored ->
            new CopyOnWriteArrayList<>()
          )
          .add(transaction);
      }

      public double retrieveBalance() {
        return 0;
      }
    };
    TransactionProducer producer = new TransactionProducer(
      account,
      new TransactionGenerator()
    );
    producer.start();
    try {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (seen.size() < 12 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      producer.close();
    }

    assertTrue(seen.size() >= 12);
    assertEquals(
      Set.of("credit-producer", "debit-producer"),
      seenByThread.keySet()
    );
    assertTrue(producer.creditsProduced() > 0);
    assertTrue(producer.debitsProduced() > 0);
    assertTrue(seen.stream().anyMatch(t -> t.amount().signum() > 0));
    assertTrue(seen.stream().anyMatch(t -> t.amount().signum() < 0));
    assertTrue(
      seenByThread
        .get("credit-producer")
        .stream()
        .allMatch(t -> t.amount().signum() > 0)
    );
    assertTrue(
      seenByThread
        .get("debit-producer")
        .stream()
        .allMatch(t -> t.amount().signum() < 0)
    );
    assertEquals(
      producer.creditsProduced(),
      seenByThread.get("credit-producer").size()
    );
    assertEquals(
      producer.debitsProduced(),
      seenByThread.get("debit-producer").size()
    );
    assertEquals(
      seen.size(),
      producer.creditsProduced() + producer.debitsProduced()
    );
    assertFalse(producer.isRunning());

    int processedAtClose = seen.size();
    Thread.sleep(100);
    assertEquals(processedAtClose, seen.size());
  }

  @Test
  void startCanOnlyBeCalledOnce() {
    BankAccountService account = noOpAccount();
    TransactionProducer producer = new TransactionProducer(
      account,
      new TransactionGenerator()
    );
    try {
      producer.start();
      assertThrows(IllegalStateException.class, producer::start);
    } finally {
      producer.close();
    }
  }

  @Test
  void processingFailureStopsBothThreads() throws Exception {
    var processed = new CopyOnWriteArrayList<Transaction>();
    BankAccountService failingAccount = new BankAccountService() {
      public void processTransaction(Transaction transaction) {
        processed.add(transaction);
        throw new IllegalStateException("simulated failure");
      }

      public double retrieveBalance() {
        return 0;
      }
    };
    TransactionProducer producer = new TransactionProducer(
      failingAccount,
      new TransactionGenerator()
    );
    try {
      producer.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (producer.isRunning() && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
      assertFalse(
        producer.isRunning(),
        "producer should stop after processing fails"
      );
    } finally {
      producer.close();
    }

    assertEquals(0, producer.creditsProduced() + producer.debitsProduced());
    int failuresAtClose = processed.size();
    Thread.sleep(100);
    assertEquals(failuresAtClose, processed.size());
  }

  @Test
  void slowProcessingDoesNotCauseCatchUpBursts() throws Exception {
    var timestampsByThread = new ConcurrentHashMap<
      String,
      CopyOnWriteArrayList<Long>
    >();
    BankAccountService slowAccount = new BankAccountService() {
      public void processTransaction(Transaction transaction) {
        timestampsByThread
          .computeIfAbsent(Thread.currentThread().getName(), ignored ->
            new CopyOnWriteArrayList<>()
          )
          .add(System.nanoTime());
        try {
          Thread.sleep(100);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }

      public double retrieveBalance() {
        return 0;
      }
    };
    TransactionProducer producer = new TransactionProducer(
      slowAccount,
      new TransactionGenerator()
    );
    try {
      producer.start();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (
        timestampsByThread
          .values()
          .stream()
          .mapToInt(CopyOnWriteArrayList::size)
          .min()
          .orElse(0) < 2 &&
        System.nanoTime() < deadline
      ) {
        Thread.sleep(10);
      }
    } finally {
      producer.close();
    }

    assertEquals(
      Set.of("credit-producer", "debit-producer"),
      timestampsByThread.keySet()
    );
    for (var timestamps : timestampsByThread.values()) {
      assertTrue(timestamps.size() >= 2);
      for (int i = 1; i < timestamps.size(); i++) {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(
          timestamps.get(i) - timestamps.get(i - 1)
        );
        assertTrue(
          elapsedMillis >= 30,
          "transactions should not be emitted in a catch-up burst"
        );
      }
    }
  }

  private static BankAccountService noOpAccount() {
    return new BankAccountService() {
      public void processTransaction(Transaction transaction) {}

      public double retrieveBalance() {
        return 0;
      }
    };
  }
}
