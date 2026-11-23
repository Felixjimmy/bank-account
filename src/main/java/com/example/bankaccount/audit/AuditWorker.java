package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.tracker.InMemoryBalanceTracker;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Objects;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class AuditWorker implements AutoCloseable {

  private final AuditGroupSource tracker;
  private final AuditPackingStrategy packer;
  private final Consumer<AuditResult> publisher;
  private final Thread thread;
  private volatile RuntimeException failure;
  private volatile List<Transaction> inFlight;
  private volatile boolean stopping;
  private final Duration shutdownTimeout;

  /** Connects the tracker to a publisher and prepares the background worker thread. */
  public AuditWorker(InMemoryBalanceTracker tracker, Consumer<AuditResult> publisher) {
    this(tracker, publisher, Duration.ofSeconds(10));
  }

  public AuditWorker(InMemoryBalanceTracker tracker, Consumer<AuditResult> publisher, Duration shutdownTimeout) {
    this(tracker, publisher, shutdownTimeout, Duration.ofSeconds(10));
  }

  public AuditWorker(InMemoryBalanceTracker tracker, Consumer<AuditResult> publisher,
      Duration shutdownTimeout, Duration exactSearchTimeout) {
    this(tracker, new AuditPacker(exactSearchTimeout), publisher, shutdownTimeout);
  }

  public AuditWorker(AuditGroupSource tracker, AuditPackingStrategy packer,
      Consumer<AuditResult> publisher, Duration shutdownTimeout) {
    this.tracker = Objects.requireNonNull(tracker, "tracker");
    this.packer = Objects.requireNonNull(packer, "packer");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
    if (shutdownTimeout.isNegative() || shutdownTimeout.isZero()) {
      throw new IllegalArgumentException("Positive shutdown timeout required");
    }
    this.shutdownTimeout = shutdownTimeout;
    this.thread = new Thread(this::run, "audit-worker");
    // An uncooperative publisher must not keep the JVM alive after the shutdown deadline.
    this.thread.setDaemon(true);
  }

  /** Starts the worker that consumes completed audit groups. */
  public void start() {
    thread.start();
  }

  /** Drains queued groups, packs each one, and publishes its result. */
  private void run() {
    try {
      Optional<List<Transaction>> group;
      while (!stopping && (group = tracker.takeCompletedGroup()).isPresent()) {
        inFlight = group.get();
        if (
          inFlight.size() != InMemoryBalanceTracker.GROUP_SIZE
        ) throw new IllegalStateException("Incomplete audit submission");
        log.info("Packing audit group: transactions={}", inFlight.size());
        AuditResult result = packer.pack(inFlight);
        if (stopping) break;
        publisher.accept(result);
        log.info(
          "Published audit group: transactions={}, batches={}, volumeLowerBound={}, exactSearchNanos={}, minimumProven={}, searchTimedOut={}",
          inFlight.size(),
          result.batches().size(),
          result.volumeLowerBound(),
          result.exactSearchNanos(),
          result.minimumProven(),
          result.searchTimedOut()
        );
        inFlight = null;
      }
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      fail(new IllegalStateException("Audit worker interrupted", interrupted));
    } catch (RuntimeException error) {
      fail(error);
    }
  }

  /** Stops further acceptance and records the worker failure for shutdown reporting. */
  private void fail(RuntimeException error) {
    failure = error;
    tracker.stopAccepting();
    log.error(
      "Audit stopped with {} transactions in flight",
      inFlight == null ? 0 : inFlight.size(),
      error
    );
  }

  /** Gives queued work a bounded drain period, then cancels and reports unfinished work. */
  @Override
  public void close() {
    tracker.stopAccepting();
    boolean interrupted = false;
    long deadline = System.nanoTime() + shutdownTimeout.toNanos();
    log.info("Draining audit work for up to {} ms", shutdownTimeout.toMillis());
    while (thread.isAlive() && System.nanoTime() < deadline) {
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) java.util.concurrent.TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
      } catch (InterruptedException e) {
        interrupted = true;
        break;
      }
    }
    if (thread.isAlive()) {
      stopping = true;
      thread.interrupt();
      List<Transaction> current = inFlight;
      String outstanding = "inFlightTransactions=" + (current == null ? 0 : current.size()) + ", " + tracker.unfinishedWork();
      log.error("Audit shutdown did not drain: {}. In-flight publication may be incomplete; in-memory work is not recoverable after exit", outstanding);
      if (interrupted) Thread.currentThread().interrupt();
      throw new IllegalStateException("Audit shutdown did not drain: " + outstanding);
    }
    log.info("Audit worker stopped: {}", tracker.unfinishedWork());
    if (interrupted) Thread.currentThread().interrupt();
    if (failure != null) throw new IllegalStateException(
      "Audit did not drain successfully",
      failure
    );
  }
}
