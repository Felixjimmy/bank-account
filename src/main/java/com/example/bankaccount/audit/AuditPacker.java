package com.example.bankaccount.audit;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import lombok.extern.slf4j.Slf4j;

import com.example.bankaccount.domain.AuditBatch;
import com.example.bankaccount.domain.AuditPlacement;
import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;

/** All capacities are exact pennies. Positions refer to the sorted input occurrences. */
@Slf4j
public final class AuditPacker {
    static final int CAP = 100_000_000;

    private final long exactSearchTimeoutNanos;
    private final java.util.function.LongSupplier nanoTime;

    public AuditPacker() { this(java.time.Duration.ofSeconds(10)); }

    public AuditPacker(java.time.Duration exactSearchTimeout) {
        this(exactSearchTimeout, System::nanoTime);
    }

    AuditPacker(java.time.Duration exactSearchTimeout, java.util.function.LongSupplier nanoTime) {
        if (exactSearchTimeout.isZero() || exactSearchTimeout.isNegative())
            throw new IllegalArgumentException("Exact search timeout must be positive");
        this.exactSearchTimeoutNanos = exactSearchTimeout.toNanos();
        this.nanoTime = java.util.Objects.requireNonNull(nanoTime);
    }

    /** Returns a valid packing, proving its minimum when possible within the search budget. */
    public AuditResult pack(List<Transaction> transactions) {
        checkCancelled();
        if (transactions.isEmpty()) {
            throw new IllegalArgumentException("Empty audit group");
        }
        var sorted = new ArrayList<>(transactions);
        sorted.sort(Comparator.comparing((Transaction t) -> t.amount().abs()).reversed());
        int[] sizes = new int[sorted.size()];
        long total = 0;
        for (int i = 0; i < sizes.length; i++) {
            BigDecimal amount = sorted.get(i).amount().abs();
            if (amount.compareTo(BigDecimal.valueOf(CAP, 2)) > 0) {
                throw new IllegalArgumentException("Transaction exceeds audit batch capacity");
            }
            sizes[i] = amount.movePointRight(2).intValueExact();
            total += sizes[i];
        }
        int volumeBound = (int) Math.ceilDiv(total, CAP);
        int lower = volumeBound;
        // At most floor(CAP / size) of the first i+1 items fit together.
        for (int i = 0; i < sizes.length; i++) {
            lower = Math.max(lower, Math.ceilDiv(i + 1, CAP / sizes[i]));
        }

        AuditPlacement best = bestFit(sizes);
        if (best.count() > lower) {
            AuditPlacement first = firstFit(sizes);
            if (first.count() < best.count()) {
                best = first;
            }
        }
        boolean needsExact = best.count() > lower;
        boolean timedOut = false;
        log.info("Audit packing bounds: transactions={}, lowerBound={}, bestBatches={}", sizes.length, lower, best.count());
        long exactStart = needsExact ? nanoTime.getAsLong() : 0;
        try {
            for (int count = lower; count < best.count(); count++) {
                checkSearchBudget(exactStart);
                log.info("Exact audit search started: transactions={}, candidateBatches={}", sizes.length, count);
                AuditPlacement feasible = exactFit(sizes, count, exactStart);
                if (feasible != null) {
                    best = feasible;
                    break;
                }
            }
        } catch (SearchTimedOut timeout) {
            timedOut = true;
        }
        long exactNanos = needsExact ? nanoTime.getAsLong() - exactStart : 0;
        checkCancelled(); // Shutdown interruption must never become a timeout fallback.
        if (timedOut) log.warn("Exact audit search timed out: minimum is NOT proven; submitting best valid packing. transactions={}, batches={}, lowerBound={}, searchDurationMs={}, timeoutMs={}",
                sizes.length, best.count(), lower, exactNanos / 1_000_000, exactSearchTimeoutNanos / 1_000_000);
        return materialize(sorted, best, volumeBound, exactNanos, !timedOut, timedOut);
    }

    private void checkSearchBudget(long started) {
        checkCancelled();
        if (nanoTime.getAsLong() - started >= exactSearchTimeoutNanos) throw new SearchTimedOut();
    }

    private static final class SearchTimedOut extends RuntimeException {
        SearchTimedOut() { super(null, null, false, false); }
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Audit packing interrupted; no optimal result published");
    }

    /** Places each descending transaction in the tightest batch with enough remaining capacity. */
    private static AuditPlacement bestFit(int[] sizes) {
        var binsBySpace = new TreeMap<Integer, ArrayDeque<Integer>>();
        int[] bin = new int[sizes.length];
        int count = 0;
        for (int i = 0; i < sizes.length; i++) {
            var fit = binsBySpace.ceilingEntry(sizes[i]);
            int space = CAP;
            if (fit == null) {
                bin[i] = count++;
            } else {
                space = fit.getKey();
                bin[i] = fit.getValue().removeFirst();
                if (fit.getValue().isEmpty()) {
                    binsBySpace.remove(space);
                }
            }
            int remaining = space - sizes[i];
            if (remaining > 0) {
                binsBySpace.computeIfAbsent(remaining, unused -> new ArrayDeque<>()).addLast(bin[i]);
            }
        }
        return new AuditPlacement(bin, count);
    }

    /**
     * Places each descending transaction in the earliest available batch.
     * Each tree node holds the largest free space in its interval of bins.
     */
    private static AuditPlacement firstFit(int[] sizes) {
        int leaves = Integer.highestOneBit(sizes.length);
        if (leaves < sizes.length) {
            leaves *= 2;
        }
        int[] max = new int[2 * leaves];
        Arrays.fill(max, CAP);
        int[] bin = new int[sizes.length];
        int count = 0;
        for (int i = 0; i < sizes.length; i++) {
            int node = 1;
            while (node < leaves) {
                node *= 2;
                if (max[node] < sizes[i]) {
                    node++;
                }
            }
            bin[i] = node - leaves;
            count = Math.max(count, bin[i] + 1);
            max[node] -= sizes[i];
            while (node > 1) {
                node /= 2;
                max[node] = Math.max(max[2 * node], max[2 * node + 1]);
            }
        }
        return new AuditPlacement(bin, count);
    }

    /** Iterative exact assignment search with capacity symmetry and necessary-condition pruning. */
    private AuditPlacement exactFit(int[] sizes, int count, long started) {
        var spaces = new TreeMap<Integer, java.util.TreeSet<Integer>>();
        for (int slot = 0; slot < count; slot++) {
            if ((slot & 1023) == 0) checkSearchBudget(started);
            addSpace(spaces, CAP, slot);
        }
        int[] bin = new int[sizes.length];
        int[] before = new int[sizes.length];
        int[] nextSpace = new int[sizes.length];
        int[] suffixGcd = new int[sizes.length];
        int[] equalRemaining = new int[sizes.length];
        long total = 0;
        for (int i = sizes.length - 1; i >= 0; i--) {
            if ((i & 1023) == 0) checkSearchBudget(started);
            total += sizes[i];
            suffixGcd[i] = i == sizes.length - 1 ? sizes[i] : gcd(sizes[i], suffixGcd[i + 1]);
            equalRemaining[i] = i + 1 < sizes.length && sizes[i] == sizes[i + 1]
                    ? equalRemaining[i + 1] + 1 : 1;
        }
        long slack = (long) count * CAP - total;
        int smallest = sizes[sizes.length - 1];
        long unusable = 0;
        var failed = new java.util.HashSet<SearchState>();
        // Cache payload is bounded to 4096 * 128 integers, plus collection overhead.
        boolean cacheEnabled = count <= 128;
        int depth = 0;
        boolean entering = true;
        long nodes = 0;
        long nextProgress = System.nanoTime() + 5_000_000_000L;
        while (depth >= 0) {
            if ((nodes++ & 1023) == 0) {
                checkSearchBudget(started);
                if (System.nanoTime() >= nextProgress) {
                    log.warn("Exact audit search still running: candidateBatches={}, placedTransactions={}/{}, searchSteps={}", count, depth, sizes.length, nodes);
                    nextProgress = System.nanoTime() + 5_000_000_000L;
                }
            }
            if (entering) {
                nextSpace[depth] = sizes[depth];
                if (unusable > slack || !remainingCanFit(spaces, sizes[depth], equalRemaining[depth],
                        suffixGcd[depth], slack)
                        || (cacheEnabled && failed.contains(state(depth, spaces, count)))) {
                    nextSpace[depth] = CAP + 1;
                }
                entering = false;
            }
            var choice = spaces.ceilingEntry(nextSpace[depth]);
            if (choice != null) {
                int capacity = choice.getKey();
                int slot = choice.getValue().first();
                // Equal remaining capacities are interchangeable regardless of bin identities.
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
                if (cacheEnabled && failed.size() < 4096) failed.add(state(depth, spaces, count));
                if (--depth < 0) break;
                int remaining = before[depth] - sizes[depth];
                removeSpace(spaces, remaining, bin[depth]);
                addSpace(spaces, before[depth], bin[depth]);
                if (remaining < smallest) unusable -= remaining;
            }
        }
        return null;
    }

    /** Rejects only states that lack capacity for remaining items or necessarily waste too much. */
    private static boolean remainingCanFit(TreeMap<Integer, java.util.TreeSet<Integer>> spaces,
                                           int largest, int equalCount, int divisor, long slack) {
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
        while (b != 0) { int remainder = a % b; a = b; b = remainder; }
        return a;
    }
    private static void addSpace(TreeMap<Integer, java.util.TreeSet<Integer>> spaces, int capacity, int slot) {
        spaces.computeIfAbsent(capacity, unused -> new java.util.TreeSet<>()).add(slot);
    }
    private static void removeSpace(TreeMap<Integer, java.util.TreeSet<Integer>> spaces, int capacity, int slot) {
        var bins = spaces.get(capacity);
        bins.remove(slot);
        if (bins.isEmpty()) spaces.remove(capacity);
    }
    private static SearchState state(int depth, TreeMap<Integer, java.util.TreeSet<Integer>> spaces, int count) {
        int[] capacities = new int[count];
        int offset = 0;
        for (var entry : spaces.entrySet()) {
            Arrays.fill(capacities, offset, offset + entry.getValue().size(), entry.getKey());
            offset += entry.getValue().size();
        }
        return new SearchState(depth, capacities);
    }
    private record SearchState(int depth, int[] capacities) {
        @Override public boolean equals(Object other) {
            return other instanceof SearchState state && depth == state.depth
                    && Arrays.equals(capacities, state.capacities);
        }
        @Override public int hashCode() { return 31 * depth + Arrays.hashCode(capacities); }
    }


    /** Builds immutable batch results and verifies that every batch respects the capacity. */
    private static AuditResult materialize(List<Transaction> sorted, AuditPlacement placement, int bound, long exactNanos, boolean minimumProven, boolean searchTimedOut) {
        var groups = new ArrayList<List<Transaction>>(placement.count());
        for (int i = 0; i < placement.count(); i++) {
            groups.add(new ArrayList<>());
        }
        // Each input position contributes exactly once, even for equal values.
        for (int i = 0; i < sorted.size(); i++) {
            groups.get(placement.bin()[i]).add(sorted.get(i));
        }
        var batches = new ArrayList<AuditBatch>();
        for (var group : groups) {
            BigDecimal total = group.stream().map(t -> t.amount().abs()).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (group.isEmpty() || total.compareTo(BigDecimal.valueOf(CAP, 2)) > 0) {
                throw new IllegalStateException("Invalid audit batch");
            }
            batches.add(new AuditBatch(List.copyOf(group), total));
        }
        return new AuditResult(List.copyOf(batches), bound, exactNanos, minimumProven, searchTimedOut);
    }
}
