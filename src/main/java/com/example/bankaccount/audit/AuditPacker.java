package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditPlacement;
import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/** All capacities are exact pennies. Positions refer to the sorted input occurrences. */
@Slf4j
public final class AuditPacker implements AuditPackingStrategy {
  static final int CAP = 100000000;

  private final long exactSearchTimeoutNanos;
  private final LongSupplier nanoTime;
  private final AuditExactSearch exactSearch;

  public AuditPacker() {
    this(java.time.Duration.ofSeconds(10));
  }

  public AuditPacker(java.time.Duration exactSearchTimeout) {
    this(exactSearchTimeout, System::nanoTime);
  }

  AuditPacker(java.time.Duration exactSearchTimeout, LongSupplier nanoTime) {
    Objects.requireNonNull(exactSearchTimeout, "exactSearchTimeout");
    if (exactSearchTimeout.isZero() || exactSearchTimeout.isNegative()) {
      throw new IllegalArgumentException("Exact search timeout must be positive");
    }
    Objects.requireNonNull(nanoTime, "nanoTime");
    this.exactSearchTimeoutNanos = exactSearchTimeout.toNanos();
    this.nanoTime = nanoTime;
    this.exactSearch = new AuditExactSearch(exactSearchTimeoutNanos, nanoTime);
  }

  /** Returns a valid packing, proving its minimum when possible within the search budget. */
  @Override
  public AuditResult pack(List<Transaction> transactions) {
    Objects.requireNonNull(transactions, "transactions");
    checkCancelled();
    if (transactions.isEmpty()) {
      throw new IllegalArgumentException("Empty audit group");
    }
    var sorted = new ArrayList<>(transactions);
    sorted.sort(Comparator.comparing((Transaction transaction) -> transaction.amount().abs()).reversed());
    int[] sizes = new int[sorted.size()];
    for (int i = 0; i < sizes.length; i++) {
      BigDecimal amount = sorted.get(i).amount().abs();
      if (amount.compareTo(BigDecimal.valueOf(CAP, 2)) > 0) {
        throw new IllegalArgumentException("Transaction exceeds audit batch capacity");
      }
      sizes[i] = amount.movePointRight(2).intValueExact();
    }
    int volumeBound = calculateVolumeBound(sizes);
    int lower = AuditLowerBoundCalculator.calculate(sizes);

    AuditPlacement best = AuditGreedyPacker.bestFit(sizes);
    if (best.count() > lower) {
      AuditPlacement first = AuditGreedyPacker.firstFit(sizes);
      if (first.count() < best.count()) {
        best = first;
      }
    }
    boolean needsExact = best.count() > lower;
    boolean timedOut = false;
    log.info("Audit packing bounds: transactions={}, lowerBound={}, bestBatches={}",
      sizes.length, lower, best.count());
    long exactStart = needsExact ? nanoTime.getAsLong() : 0;
    try {
      for (int count = lower; count < best.count(); count++) {
        checkSearchBudget(exactStart);
        log.info("Exact audit search started: transactions={}, candidateBatches={}",
          sizes.length, count);
        AuditPlacement feasible = exactSearch.find(sizes, count, exactStart);
        if (feasible != null) {
          best = feasible;
          break;
        }
      }
    } catch (AuditExactSearch.SearchTimedOut timeout) {
      timedOut = true;
    }
    long exactNanos = needsExact ? nanoTime.getAsLong() - exactStart : 0;
    checkCancelled();
    if (timedOut) {
      log.warn("Exact audit search timed out: minimum is NOT proven; submitting best valid packing. transactions={}, batches={}, lowerBound={}, searchDurationMs={}, timeoutMs={}",
        sizes.length, best.count(), lower, exactNanos / 1000000,
        exactSearchTimeoutNanos / 1000000);
    }
    return AuditResultMaterializer.materialize(sorted, best, volumeBound,
      exactNanos, !timedOut, timedOut);
  }

  private int calculateVolumeBound(int[] sizes) {
    long total = 0;
    for (int size : sizes) {
      total += size;
    }
    return (int) Math.ceilDiv(total, CAP);
  }

  private void checkSearchBudget(long started) {
    checkCancelled();
    if (nanoTime.getAsLong() - started >= exactSearchTimeoutNanos) {
      throw new AuditExactSearch.SearchTimedOut();
    }
  }

  private static void checkCancelled() {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException(
        "Audit packing interrupted; no optimal result published");
    }
  }
}
