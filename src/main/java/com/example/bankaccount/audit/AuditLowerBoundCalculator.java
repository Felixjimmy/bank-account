package com.example.bankaccount.audit;

final class AuditLowerBoundCalculator {
  private AuditLowerBoundCalculator() {}

  static int calculate(int[] sizes) {
    long total = 0;
    for (int size : sizes) {
      total += size;
    }
    int lower = (int) Math.ceilDiv(total, AuditPacker.CAP);
    for (int i = 0; i < sizes.length; i++) {
      lower = Math.max(lower, Math.ceilDiv(i + 1, AuditPacker.CAP / sizes[i]));
    }
    return lower;
  }
}
