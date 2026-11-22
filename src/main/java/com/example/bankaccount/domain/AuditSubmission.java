package com.example.bankaccount.domain;

import java.util.List;
import java.util.UUID;

public record AuditSubmission(
  UUID submissionId,
  int batchCount,
  int volumeLowerBound,
  boolean minimumProven,
  boolean searchTimedOut,
  List<AuditBatchSummary> batches
) {}
