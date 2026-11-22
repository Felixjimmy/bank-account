package com.example.bankaccount.domain;

import java.math.BigDecimal;
import java.util.List;

public record AuditBatch(
  List<Transaction> transactions,
  BigDecimal totalValueOfAllTransactions
) {}
