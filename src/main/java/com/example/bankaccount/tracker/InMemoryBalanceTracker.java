package com.example.bankaccount.tracker;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.service.BankAccountService;
import java.math.BigDecimal;
import com.example.bankaccount.audit.AuditGroupSource;
import java.util.List;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public final class InMemoryBalanceTracker implements BankAccountService, AuditGroupSource {

  public static final int GROUP_SIZE = 1_000;
  private final int maxQueuedAuditGroups;
  private final ArrayDeque<List<Transaction>> completed = new ArrayDeque<>();
  private ArrayList<Transaction> pending = new ArrayList<>(GROUP_SIZE);
  private BigDecimal balance = new BigDecimal("0.00");
  private boolean closed;

  /** Creates a tracker with a positive maximum number of queued audit groups. */
  public InMemoryBalanceTracker(
    @Value(
      "${bank-account.tracker.max-queued-audit-groups}"
    ) int maxQueuedAuditGroups
  ) {
    if (maxQueuedAuditGroups < 1) {
      throw new IllegalArgumentException(
        "bank-account.tracker.max-queued-audit-groups must be positive"
      );
    }
    this.maxQueuedAuditGroups = maxQueuedAuditGroups;
  }

  /** Atomically updates the balance and pending group, waiting before a full queue would be exceeded. */
  @Override
  public synchronized void processTransaction(Transaction transaction) {
    Objects.requireNonNull(transaction, "transaction");
    // Wait before accepting the closing transaction so the balance and audit group agree.
    boolean waited = false;
    while (
      !closed &&
      pending.size() == GROUP_SIZE - 1 &&
      completed.size() >= maxQueuedAuditGroups
    ) {
      if (!waited) {
        log.warn(
          "Audit queue full at {} groups; waiting before accepting transaction {}",
          maxQueuedAuditGroups,
          transaction.id()
        );
        waited = true;
      }
      try {
        wait();
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(
          "Interrupted before transaction acceptance",
          interrupted
        );
      }
    }
    if (closed) {
      throw new IllegalStateException("Balance tracker is closing");
    }
    if (waited) {
      log.info(
        "Audit queue space available; accepting transaction {}",
        transaction.id()
      );
    }
    balance = balance.add(transaction.amount());
    pending.add(transaction);
    log.debug(
      "Accepted transaction {} amount GBP {}; pending group size {}/{}",
      transaction.id(),
      transaction.amount(),
      pending.size(),
      GROUP_SIZE
    );
    if (pending.size() == GROUP_SIZE) {
      completed.addLast(java.util.Collections.unmodifiableList(pending));
      pending = new ArrayList<>(GROUP_SIZE);
      log.info(
        "Completed audit group queued: transactions={}, queuedGroups={}",
        GROUP_SIZE,
        completed.size()
      );
      notifyAll();
    }
  }

  /** Waits for and removes the next completed group, or returns empty after shutdown and drain. */
  public synchronized Optional<
    List<Transaction>
  > takeCompletedGroup() throws InterruptedException {
    while (completed.isEmpty() && !closed) wait();
    return pollCompletedGroup();
  }

  /** Removes the next completed group without waiting. */
  public synchronized Optional<List<Transaction>> pollCompletedGroup() {
    List<Transaction> group = completed.pollFirst();
    if (group != null) {
      notifyAll();
    }
    return Optional.ofNullable(group);
  }

  /** Returns a synchronized snapshot of the exact balance. */
  public synchronized BigDecimal exactBalance() {
    return balance;
  }

  /** Snapshot of work still owned by the tracker, excluding the worker's current group. */
  public synchronized String unfinishedWork() {
    return "queuedGroups=" + completed.size() + ", partialTransactions=" + pending.size();
  }

  /** Converts the exact balance to the double required by the service interface. */
  @Override
  public double retrieveBalance() {
    return exactBalance().doubleValue();
  }

  /** Stops acceptance and wakes threads waiting for queue capacity or completed work. */
  @EventListener(ContextClosedEvent.class)
  public synchronized void stopAccepting() {
    if (closed) return;
    closed = true;
    notifyAll();
    log.info(
      "Balance tracker closing with {} completed groups and {} pending transactions",
      completed.size(),
      pending.size()
    );
  }
}
