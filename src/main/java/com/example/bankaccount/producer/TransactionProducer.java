package com.example.bankaccount.producer;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.domain.TransactionType;
import com.example.bankaccount.service.BankAccountService;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TransactionProducer implements AutoCloseable {

  private static final long INTERVAL_NANOS = Duration.ofMillis(40).toNanos();
  private static final long REPORT_NANOS = Duration.ofMinutes(1).toNanos();

  private final BankAccountService account;
  private final TransactionGenerator generator;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicBoolean started = new AtomicBoolean();
  private final AtomicLong credits = new AtomicLong();
  private final AtomicLong debits = new AtomicLong();
  private volatile RuntimeException failure;
  private Thread creditThread;
  private Thread debitThread;

  /** Stores the account service and transaction generator used by both producer threads. */
  public TransactionProducer(
    BankAccountService account,
    TransactionGenerator generator
  ) {
    this.account = Objects.requireNonNull(account);
    this.generator = Objects.requireNonNull(generator);
  }

  /** Starts one credit thread and one debit thread; a producer instance can only start once. */
  public synchronized void start() {
    if (!started.compareAndSet(false, true)) {
      throw new IllegalStateException("Producer has already started");
    }
    running.set(true);
    creditThread = new Thread(
      () -> produce(TransactionType.CREDIT, credits),
      "credit-producer"
    );
    debitThread = new Thread(
      () -> produce(TransactionType.DEBIT, debits),
      "debit-producer"
    );
    creditThread.start();
    debitThread.start();
    log.info(
      "Started credit and debit producers at a target of 25 transactions per second each"
    );
  }

  /** Produces transactions at a steady cadence and stops both threads after a processing failure. */
  private void produce(TransactionType type, AtomicLong count) {
    long next = System.nanoTime();
    long nextReport = next + REPORT_NANOS;
    long missedIntervals = 0;
    try {
      while (running.get()) {
        long wait = next - System.nanoTime();
        if (wait > 0) {
          LockSupport.parkNanos(wait);
        }
        if (!running.get() || Thread.currentThread().isInterrupted()) {
          break;
        }

        Transaction transaction = generator.generate(type);
        try {
          account.processTransaction(transaction);
        } catch (RuntimeException failure) {
          log.error(
            "{} failed processing transaction {}",
            type,
            transaction.id(),
            failure
          );
          recordFailure(failure);
          stopBoth();
          break;
        }
        count.incrementAndGet();
        log.debug(
          "Produced {} transaction {} amount GBP {}",
          type,
          transaction.id(),
          transaction.amount()
        );

        long now = System.nanoTime();
        next += INTERVAL_NANOS;
        if (next <= now) {
          missedIntervals += Math.max(1, (now - next) / INTERVAL_NANOS + 1);
          next = now + INTERVAL_NANOS;
        }
        if (now >= nextReport) {
          if (missedIntervals > 0) {
            log.warn(
              "{} producer missed {} scheduled intervals in the last reporting period",
              type,
              missedIntervals
            );
          }
          missedIntervals = 0;
          nextReport = now + REPORT_NANOS;
        }
      }
    } catch (RuntimeException failure) {
      log.error("{} producer failed", type, failure);
      recordFailure(failure);
      stopBoth();
    }
  }

  /** Signals both producer threads to stop and wakes any thread waiting between transactions. */
  private void stopBoth() {
    running.set(false);
    if (creditThread != null) {
      LockSupport.unpark(creditThread);
    }
    if (debitThread != null) {
      LockSupport.unpark(debitThread);
    }
  }

  private void recordFailure(RuntimeException failure) {
    if (this.failure == null) {
      this.failure = failure;
    }
  }

  /** Stops both producers and waits for their threads to finish. */
  @Override
  public void close() {
    stopBoth();
    join(creditThread, Duration.ofSeconds(5));
    join(debitThread, Duration.ofSeconds(5));
    log.info(
      "Stopped producers after {} credits and {} debits",
      credits.get(),
      debits.get()
    );
    if (failure != null) {
      log.error("Transaction producer stopped after a failure", failure);
    }
  }

  /** Waits for a producer thread to exit while restoring this thread's interrupt status. */
  private static void join(Thread thread, Duration timeout) {
    if (thread == null || thread == Thread.currentThread()) {
      return;
    }
    boolean interrupted = false;
    long deadline = System.nanoTime() + timeout.toNanos();
    while (thread.isAlive() && System.nanoTime() < deadline) {
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) {
          thread.join(TimeUnit.NANOSECONDS.toMillis(remaining));
        }
      } catch (InterruptedException e) {
        interrupted = true;
      }
    }
    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  /** Returns the number of credit transactions accepted by the account service. */
  public long creditsProduced() {
    return credits.get();
  }

  /** Returns the number of debit transactions accepted by the account service. */
  public long debitsProduced() {
    return debits.get();
  }

  /** Reports whether the producer threads are still allowed to generate transactions. */
  public boolean isRunning() {
    return running.get();
  }

  /** Returns the first runtime failure recorded by the producer threads, if any. */
  public RuntimeException failure() {
    return failure;
  }
}
