package com.example.bankaccount.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionTest {

  @Test
  void requiresAnIdAndAmount() {
    assertThrows(NullPointerException.class, () ->
      new Transaction(null, BigDecimal.ONE)
    );
    assertThrows(NullPointerException.class, () ->
      new Transaction(UUID.randomUUID(), null)
    );
  }

  @Test
  void rejectsZeroAndAmountsWithMoreThanTwoDecimalPlaces() {
    assertThrows(IllegalArgumentException.class, () ->
      new Transaction(UUID.randomUUID(), new BigDecimal("0.00"))
    );
    assertThrows(IllegalArgumentException.class, () ->
      new Transaction(UUID.randomUUID(), new BigDecimal("1.001"))
    );
  }

  @Test
  void normalizesValidAmountsToPennyPrecision() {
    Transaction transaction = new Transaction(
      UUID.randomUUID(),
      new BigDecimal("12")
    );

    assertEquals(new BigDecimal("12.00"), transaction.amount());
    assertEquals(2, transaction.amount().scale());
  }
}
