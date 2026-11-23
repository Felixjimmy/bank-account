package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditBatch;
import com.example.bankaccount.domain.AuditPlacement;
import com.example.bankaccount.domain.AuditResult;
import com.example.bankaccount.domain.Transaction;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

final class AuditResultMaterializer {
  private AuditResultMaterializer() {}

  static AuditResult materialize(List<Transaction> sorted, AuditPlacement placement,
      int bound, long exactNanos, boolean minimumProven, boolean searchTimedOut) {
    var groups = new ArrayList<List<Transaction>>(placement.count());
    for (int i = 0; i < placement.count(); i++) {
      groups.add(new ArrayList<>());
    }
    for (int i = 0; i < sorted.size(); i++) {
      groups.get(placement.bin()[i]).add(sorted.get(i));
    }
    var batches = new ArrayList<AuditBatch>();
    for (var group : groups) {
      BigDecimal total = group.stream()
        .map(t -> t.amount().abs())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
      if (group.isEmpty() || total.compareTo(BigDecimal.valueOf(AuditPacker.CAP, 2)) > 0) {
        throw new IllegalStateException("Invalid audit batch");
      }
      batches.add(new AuditBatch(List.copyOf(group), total));
    }
    return new AuditResult(List.copyOf(batches), bound, exactNanos,
      minimumProven, searchTimedOut);
  }
}
