package com.example.bankaccount.domain;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

public record Transaction(UUID id, BigDecimal amount) {
  /** Requires an ID and a non-zero amount with at most two decimal places. */
  public Transaction {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(amount, "amount");
    if (amount.scale() > 2 || amount.signum() == 0) {
      throw new IllegalArgumentException(
        "Amount must be non-zero GBP with at most two decimal places"
      );
    }
    amount = amount.setScale(2);
  }
}
