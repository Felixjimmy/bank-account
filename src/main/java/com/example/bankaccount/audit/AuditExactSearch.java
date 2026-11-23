package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditPlacement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;

/** Tries to fit sorted item sizes into a fixed number of batches. */
@Slf4j
final class AuditExactSearch {
  private final long timeoutNanos;
  private final LongSupplier nanoTime;

  AuditExactSearch(long timeoutNanos, LongSupplier nanoTime) {
    this.timeoutNanos = timeoutNanos;
    this.nanoTime = nanoTime;
  }

  static final class SearchTimedOut extends RuntimeException {
    SearchTimedOut() {
      super(null, null, false, false);
    }
  }

  // A null result means every assignment was ruled out; a timeout throws instead.
  AuditPlacement find(int[] sizes, int count, long started) {
    var spaces = new TreeMap<Integer, TreeSet<Integer>>();
    for (int slot = 0; slot < count; slot++) {
      if ((slot & 1023) == 0) checkBudget(started);
      addSpace(spaces, AuditPacker.CAP, slot);
    }
    int[] bin = new int[sizes.length];
    int[] before = new int[sizes.length];
    int[] nextSpace = new int[sizes.length];
    int[] suffixGcd = new int[sizes.length];
    int[] equalRemaining = new int[sizes.length];
    long total = 0;
    for (int i = sizes.length - 1; i >= 0; i--) {
      if ((i & 1023) == 0) checkBudget(started);
      total += sizes[i];
      suffixGcd[i] = i == sizes.length - 1 ? sizes[i] : gcd(sizes[i], suffixGcd[i + 1]);
      equalRemaining[i] = i + 1 < sizes.length && sizes[i] == sizes[i + 1]
        ? equalRemaining[i + 1] + 1 : 1;
    }
    // Slack is all the empty space this candidate batch count can afford.
    long slack = (long) count * AuditPacker.CAP - total;
    int smallest = sizes[sizes.length - 1];
    long unusable = 0;
    var failed = new HashSet<SearchState>();
    // Each cached state stores one capacity per batch; disable caching for large counts.
    boolean cacheEnabled = count <= 128;
    int depth = 0;
    boolean entering = true;
    long nodes = 0;
    long nextProgress = nanoTime.getAsLong() + 5000000000L;
    while (depth >= 0) {
      if ((nodes++ & 1023) == 0) {
        checkBudget(started);
        if (nanoTime.getAsLong() >= nextProgress) {
          log.warn("Exact audit search still running: candidateBatches={}, placedTransactions={}/{}, searchSteps={}",
            count, depth, sizes.length, nodes);
          nextProgress = nanoTime.getAsLong() + 5000000000L;
        }
      }
      if (entering) {
        // These checks only reject states that cannot fit the remaining items.
        // Space below the smallest item is permanently unusable.
        nextSpace[depth] = sizes[depth];
        if (unusable > slack || !remainingCanFit(spaces, sizes[depth],
            equalRemaining[depth], suffixGcd[depth], slack)
          || (cacheEnabled && failed.contains(state(depth, spaces, count)))) {
          nextSpace[depth] = AuditPacker.CAP + 1;
        }
        entering = false;
      }
      // Equal free capacities are interchangeable: trying one representative
      // avoids repeating the same packing with different batch labels.
      var choice = spaces.ceilingEntry(nextSpace[depth]);
      if (choice != null) {
        int capacity = choice.getKey();
        int slot = choice.getValue().first();
        nextSpace[depth] = capacity + 1;
        before[depth] = capacity;
        bin[depth] = slot;
        removeSpace(spaces, capacity, slot);
        int remaining = capacity - sizes[depth];
        addSpace(spaces, remaining, slot);
        if (remaining < smallest) unusable += remaining;
        if (++depth == sizes.length) return new AuditPlacement(bin, count);
        entering = true;
      } else {
        // No capacity choice worked here. Remember the failure, undo the
        // previous placement, and try that item's next distinct capacity.
        if (cacheEnabled && failed.size() < 4096) {
          failed.add(state(depth, spaces, count));
        }
        if (--depth < 0) break;
        int remaining = before[depth] - sizes[depth];
        removeSpace(spaces, remaining, bin[depth]);
        addSpace(spaces, before[depth], bin[depth]);
        if (remaining < smallest) unusable -= remaining;
      }
    }
    return null;
  }

  private void checkBudget(long started) {
    if (Thread.currentThread().isInterrupted()) {
      throw new CancellationException("Audit packing interrupted; no optimal result published");
    }
    if (nanoTime.getAsLong() - started >= timeoutNanos) {
      throw new SearchTimedOut();
    }
  }

  private static boolean remainingCanFit(TreeMap<Integer, TreeSet<Integer>> spaces,
      int largest, int equalCount, int divisor, long slack) {
    // Every remaining item must fit somewhere. The equal largest items need
    // enough total slots, and items divisible by 'divisor' leave at least
    // (free space % divisor) unused in each batch.
    if (spaces.lastKey() < largest) return false;
    if (equalCount == 1 && divisor == 1) return true;
    long slots = 0;
    long residues = 0;
    for (var entry : spaces.entrySet()) {
      int multiplicity = entry.getValue().size();
      slots += (long) (entry.getKey() / largest) * multiplicity;
      residues += (long) (entry.getKey() % divisor) * multiplicity;
      if (residues > slack) return false;
    }
    return slots >= equalCount;
  }

  private static int gcd(int a, int b) {
    while (b != 0) {
      int remainder = a % b;
      a = b;
      b = remainder;
    }
    return a;
  }

  private static void addSpace(TreeMap<Integer, TreeSet<Integer>> spaces, int capacity, int slot) {
    spaces.computeIfAbsent(capacity, unused -> new TreeSet<>()).add(slot);
  }

  private static void removeSpace(TreeMap<Integer, TreeSet<Integer>> spaces,
      int capacity, int slot) {
    var bins = spaces.get(capacity);
    bins.remove(slot);
    if (bins.isEmpty()) spaces.remove(capacity);
  }

  private static SearchState state(int depth, TreeMap<Integer, TreeSet<Integer>> spaces, int count) {
    // Store sorted free capacities, not batch IDs: relabelled batches are the same state.
    int[] capacities = new int[count];
    int offset = 0;
    for (var entry : spaces.entrySet()) {
      Arrays.fill(capacities, offset, offset + entry.getValue().size(), entry.getKey());
      offset += entry.getValue().size();
    }
    return new SearchState(depth, capacities);
  }

  private record SearchState(int depth, int[] capacities) {
    @Override
    public boolean equals(Object other) {
      return other instanceof SearchState state && depth == state.depth
        && Arrays.equals(capacities, state.capacities);
    }

    @Override
    public int hashCode() {
      return 31 * depth + Arrays.hashCode(capacities);
    }
  }
}
