package com.example.bankaccount.domain;

import java.util.List;

public record AuditResult(
  List<AuditBatch> batches,
  int volumeLowerBound,
  long exactSearchNanos,
  boolean minimumProven,
  boolean searchTimedOut
) {}
