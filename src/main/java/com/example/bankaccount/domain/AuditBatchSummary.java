package com.example.bankaccount.domain;

import java.math.BigDecimal;

public record AuditBatchSummary(
  BigDecimal totalValueOfAllTransactions,
  int countOfTransactions
) {}
