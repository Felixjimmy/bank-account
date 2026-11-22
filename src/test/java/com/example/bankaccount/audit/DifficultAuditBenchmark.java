package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Opt-in adversarial measurement; each sample runs in a disposable JVM. */
public final class DifficultAuditBenchmark {
    static List<Transaction> randomInput(int seed) {
        var random = new Random(seed);
        var input = new ArrayList<Transaction>();
        for (int i = 0; i < 1000; i++) {
            int pennies = random.nextInt(20_000, 50_000_001);
            input.add(new Transaction(new UUID(seed, i), BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)));
        }
        return input;
    }
    static List<Transaction> balanced(int size) {
        if (size < 8 || size % 4 != 0) throw new IllegalArgumentException("Size must be a multiple of four, at least eight");
        var input = new ArrayList<Transaction>(size);
        for (int i = 0; i < size; i++) {
            long pennies = i < size / 2 ? 25_000_001 : 24_999_999;
            input.add(new Transaction(new UUID(0, i), BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)));
        }
        return input;
    }

    static void validate(List<Transaction> input, AuditResult result, int expected) {
        if (expected >= 0 && result.batches().size() != expected) throw new AssertionError("Wrong minimum batch count");
        var remaining = new HashMap<UUID, Transaction>();
        input.forEach(t -> remaining.put(t.id(), t));
        for (var batch : result.batches()) {
            var total = BigDecimal.ZERO;
            for (var transaction : batch.transactions()) {
                if (!transaction.equals(remaining.remove(transaction.id()))) throw new AssertionError("Lost, changed or repeated transaction");
                total = total.add(transaction.amount().abs());
            }
            if (batch.transactions().isEmpty() || total.compareTo(new BigDecimal("1000000")) > 0
                    || total.compareTo(batch.totalValueOfAllTransactions()) != 0) throw new AssertionError("Invalid batch");
        }
        if (!remaining.isEmpty()) throw new AssertionError("Missing transactions");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("sample")) {
            String scenario = args[1];
            int size = Integer.parseInt(args[2]);
            List<Transaction> input;
            int expected;
            if (scenario.equals("random")) {
                input = randomInput(size);
                expected = -1; // No independently known optimum for arbitrary random input.
            } else if (scenario.equals("balanced")) {
                input = balanced(size);
                expected = size / 4; // Two amounts of each magnitude fill every batch exactly.
            } else {
                int[] units = scenario.equals("repair")
                        ? new int[]{40,38,33,27,26,19,17}
                        : new int[]{17,46,42,27,21,32,15};
                input = new ArrayList<>();
                for (int i = 0; i < units.length; i++) input.add(new Transaction(new UUID(0, i),
                        BigDecimal.valueOf((i % 2 == 0 ? 1L : -1L) * units[i] * 10_000)));
                expected = scenario.equals("repair") ? 2 : 3;
            }
            System.out.printf("START scenario=%s parameter=%d n=%d expected=%d%n", scenario, size, input.size(), expected);
            System.out.flush();
            long start = System.nanoTime();
            var result = new AuditPacker().pack(input);
            long elapsed = System.nanoTime() - start;
            validate(input, result, result.minimumProven() ? expected : -1);
            if (!scenario.equals("random") && result.exactSearchNanos() <= 0) throw new AssertionError("Fixture did not exercise optimisation");
            System.out.printf(Locale.ROOT, "RESULT scenario=%s n=%d batches=%d bound=%d packMs=%.3f exactMs=%.3f minimumProven=%s searchTimedOut=%s%n",
                    scenario, input.size(), result.batches().size(), result.volumeLowerBound(), elapsed / 1e6,
                    result.exactSearchNanos() / 1e6, result.minimumProven(), result.searchTimedOut());
            return;
        }
        int repeats = args.length == 0 ? 3 : Integer.parseInt(args[0]);
        if (repeats < 1) throw new IllegalArgumentException("Positive repeat count required");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String[][] cases = {{"repair", "7"}, {"impossible", "7"}, {"balanced", "8"},
                {"balanced", "24"}, {"balanced", "1000"}, {"balanced", "100000"},
                {"random", "0"}, {"random", "1"}, {"random", "2"}, {"random", "3"}, {"random", "4"},
                {"random", "5"}, {"random", "6"}, {"random", "7"}, {"random", "8"}, {"random", "9"}};
        for (var scenario : cases) for (int sample = 1; sample <= repeats; sample++) {
            Path output = Files.createTempFile("difficult-audit-", ".log");
            Process process = null;
            try {
                process = new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
                        DifficultAuditBenchmark.class.getName(), "sample", scenario[0], scenario[1])
                        .redirectErrorStream(true).redirectOutput(output.toFile()).start();
                boolean finished = process.waitFor(3, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                System.out.printf("SAMPLE %d %s", sample, Files.readString(output));
                if (!finished) System.out.printf("TIMEOUT scenario=%s parameter=%s processBudgetMs=3000%n", scenario[0], scenario[1]);
                else if (process.exitValue() != 0) throw new IllegalStateException("Benchmark child failed: " + process.exitValue());
            } finally {
                if (process != null && process.isAlive()) {
                    process.destroyForcibly();
                    process.waitFor();
                }
                Files.deleteIfExists(output);
            }
        }
    }
}
