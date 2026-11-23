package com.example.bankaccount.audit;

import com.example.bankaccount.domain.Transaction;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.*;

/** Run after test compilation with java -cp "target/test-classes;target/classes" com.example.bankaccount.audit.AuditScale 100000 */
public final class AuditScale {

  public static void main(String[] args) {
    int size = Integer.parseInt(args[0]);
    var random = new Random(317);
    var input = new ArrayList<Transaction>(size);
    for (int i = 0; i < size; i++) {
      int pennies = random.nextInt(20000, 50000001);
      input.add(
        new Transaction(
          new UUID(0, i),
          BigDecimal.valueOf(i % 2 == 0 ? pennies : -pennies, 2)
        )
      );
    }
    var memory = ManagementFactory.getPlatformMXBean(ThreadMXBean.class);
    long before = memory.getCurrentThreadAllocatedBytes();
    long start = System.nanoTime();
    var result = new AuditPacker().pack(input);
    long elapsed = System.nanoTime() - start;
    long allocated = memory.getCurrentThreadAllocatedBytes() - before;
    System.out.printf(
      "transactions=%d batches=%d volumeBound=%d timeMs=%.2f exactMs=%.2f allocatedMiB=%.2f minimumProven=%s searchTimedOut=%s%n",
      size,
      result.batches().size(),
      result.volumeLowerBound(),
      elapsed / 1e6,
      result.exactSearchNanos() / 1e6,
      allocated / 1048576.0,
      result.minimumProven(),
      result.searchTimedOut()
    );
  }
}
