package com.example.bankaccount.audit;

import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

class AuditPackerTest {

  private final AuditPacker packer = new AuditPacker();

  @Test
  void deadlineDuringSearchReturnsCompleteValidPackingWithoutClaimingMinimum() {
    for (int seed : new int[]{6, 9}) {
      var clock = new java.util.concurrent.atomic.AtomicLong();
      var timed = new AuditPacker(java.time.Duration.ofMillis(5),
          () -> clock.getAndAdd(1_000_000));
      var input = DifficultAuditBenchmark.randomInput(seed);
      var result = timed.pack(input);
      assertTrue(result.searchTimedOut());
      assertFalse(result.minimumProven());
      assertTrue(result.exactSearchNanos() >= 5_000_000);
      assertEquals(seed == 6 ? 245 : 241, result.batches().size());
      DifficultAuditBenchmark.validate(input, result, -1);
    }
  }

  @Test
  void greedyProofDoesNotConsumeExactSearchBudget() {
    var timed = new AuditPacker(java.time.Duration.ofNanos(1),
        () -> { throw new AssertionError("Greedy proof should not start search clock"); });
    var result = timed.pack(List.of(new Transaction(UUID.randomUUID(), new BigDecimal("500000.00"))));
    assertTrue(result.minimumProven());
    assertFalse(result.searchTimedOut());
    assertEquals(0, result.exactSearchNanos());
  }

  @Test
  void rejectsNonpositiveExactSearchTimeout() {
    assertThrows(IllegalArgumentException.class, () -> new AuditPacker(java.time.Duration.ZERO));
    assertThrows(IllegalArgumentException.class, () -> new AuditPacker(java.time.Duration.ofSeconds(-1)));
  }

  @Test
  void rejectsNullTimeoutAndInput() {
    assertThrows(NullPointerException.class, () -> new AuditPacker(null));
    assertThrows(NullPointerException.class, () -> packer.pack(null));
  }

  @Test
  void interruptedPackingNeverReturnsAnUnprovedResult() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(java.util.concurrent.CancellationException.class,
          () -> packer.pack(DifficultAuditBenchmark.balanced(1000)));
    } finally { Thread.interrupted(); }
  }

  @Test
  void seededRandomInputsEitherFinishValidlyOrCooperateWithCancellation() throws Exception {
    for (int seed : new int[]{6, 9}) {
      var input = DifficultAuditBenchmark.randomInput(seed);
      var finished = new java.util.concurrent.CountDownLatch(1);
      var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
      var thread = new Thread(() -> {
        try {
          var result = packer.pack(input);
          DifficultAuditBenchmark.validate(input, result, -1);
          assertTrue(result.batches().size() >= result.volumeLowerBound());
        } catch (java.util.concurrent.CancellationException expected) {
          assertTrue(Thread.currentThread().isInterrupted());
        } catch (Throwable failure) { error.set(failure); }
        finally { finished.countDown(); }
      });
      thread.setDaemon(true);
      thread.start();
      try {
        if (!finished.await(200, java.util.concurrent.TimeUnit.MILLISECONDS)) thread.interrupt();
        assertTrue(finished.await(2, java.util.concurrent.TimeUnit.SECONDS), "Search ignored cancellation");
        assertNull(error.get(), () -> String.valueOf(error.get()));
      } finally { thread.interrupt(); }
    }
  }

  @Test
  void largeDifficultSubmissionsRetainEveryTransactionAtTheProvenMinimum() {
    for (int size : new int[]{1000, 100000}) {
      var input = DifficultAuditBenchmark.balanced(size);
      var result = packer.pack(input);
      DifficultAuditBenchmark.validate(input, result, size / 4);
      assertEquals(size / 4, result.volumeLowerBound());
    }
  }

  @Test
  void pruningAgreesWithIndependentPartitionsForPenniesDuplicatesAndSlack() {
    var random = new Random(6281);
    int[] boundaries = {20_000, 24_999_999, 25_000_001, 33_333_333, 33_333_334, 50_000_000};
    for (int trial = 0; trial < 600; trial++) {
      int[] amounts = new int[6 + random.nextInt(4)];
      for (int i = 0; i < amounts.length; i++) {
        amounts[i] = trial % 2 == 0 ? boundaries[random.nextInt(boundaries.length)]
            : random.nextInt(20_000, 50_000_001);
      }
      int expected = minimum(amounts, 0, new int[amounts.length], 0);
      int[] signed = amounts.clone();
      for (int i = 1; i < signed.length; i += 2) signed[i] = -signed[i];
      assertEquals(expected, pack(signed).batches().size(), "Trial " + trial);
    }
  }

  @Test
  void pennySeparatedAmountsRequireExactPackingRegardlessOfInputOrder() {
    for (int size : new int[]{8, 16, 24}) {
      var input = DifficultAuditBenchmark.balanced(size);
      for (int seed = 0; seed < 3; seed++) {
        Collections.shuffle(input, new Random(seed));
        var result = packer.pack(input);
        DifficultAuditBenchmark.validate(input, result, size / 4);
        assertEquals(size / 4, result.volumeLowerBound());
        assertTrue(result.exactSearchNanos() > 0, "Must exercise the exact fallback");
      }
    }
  }

  private AuditResult pack(int... pennies) {
    var input = new ArrayList<Transaction>();
    for (int i = 0; i < pennies.length; i++) input.add(
      new Transaction(new UUID(0, i), BigDecimal.valueOf(pennies[i], 2))
    );
    var result = packer.pack(input);
    assertTrue(result.minimumProven());
    assertFalse(result.searchTimedOut());
    var retained = new ArrayList<Transaction>();
    for (var batch : result.batches()) {
      assertFalse(batch.transactions().isEmpty());
      var sum = batch
        .transactions()
        .stream()
        .map(t -> t.amount().abs())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
      assertEquals(sum, batch.totalValueOfAllTransactions());
      assertTrue(sum.compareTo(new BigDecimal("1000000.00")) <= 0);
      retained.addAll(batch.transactions());
    }
    retained.sort(Comparator.comparing(Transaction::id));
    assertEquals(input, retained);
    return result;
  }

  @Test
  void respectsAbsoluteValueAndTheLastPenny() {
    assertEquals(1, pack(50_000_000, -50_000_000).batches().size());
    assertEquals(2, pack(50_000_000, -50_000_000, 1).batches().size());
    assertThrows(IllegalArgumentException.class, () -> pack(100_000_001));
    assertThrows(IllegalArgumentException.class, () -> pack());
  }

  @Test
  void exactSearchImprovesBothGreedySolutions() {
    // Greedy opens three bins. 40+27+33 and 38+26+19+17 fill two.
    assertEquals(
      2,
      pack(
        40_000_000,
        38_000_000,
        33_000_000,
        27_000_000,
        26_000_000,
        19_000_000,
        17_000_000
      )
        .batches()
        .size()
    );
  }

  @Test
  void provesMinimumAboveVolumeBound() {
    var result = pack(
      40_000_000,
      40_000_000,
      40_000_000,
      40_000_000,
      40_000_000
    );
    assertEquals(2, result.volumeLowerBound());
    assertEquals(3, result.batches().size());
  }

  @Test
  void rulesOutTwoBinsWhenNoSubsetFillsOneExactly() {
    var result = pack(
      17_000_000,
      46_000_000,
      42_000_000,
      27_000_000,
      21_000_000,
      32_000_000,
      15_000_000
    );
    assertEquals(2, result.volumeLowerBound());
    assertEquals(3, result.batches().size());
  }

  @Test
  void matchesExhaustivePartitionsOnSmallGroups() {
    var random = new Random(317);
    for (int trial = 0; trial < 150; trial++) {
      int[] amounts = random
        .ints(8, 1, 51)
        .map(v -> v * 1_000_000)
        .toArray();
      assertEquals(
        minimum(amounts, 0, new int[amounts.length], 0),
        pack(amounts).batches().size()
      );
    }
  }

  private int minimum(int[] amounts, int pos, int[] used, int open) {
    if (pos == amounts.length) return open;
    int best = amounts.length;
    for (int i = 0; i <= open; i++) {
      if (used[i] + amounts[pos] <= 100_000_000) {
        used[i] += amounts[pos];
        best = Math.min(
          best,
          minimum(amounts, pos + 1, used, Math.max(i + 1, open))
        );
        used[i] -= amounts[pos];
      }
    }
    return best;
  }
}
