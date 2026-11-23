package com.example.bankaccount.producer;

import static org.junit.jupiter.api.Assertions.*;

import com.example.bankaccount.domain.TransactionType;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TransactionGeneratorTest {

  /** Samples 1,000 generated credits and checks non-null IDs, penny precision and the allowed amount range. */
  @Test
  void defaultGeneratorProducesRandomIdsAndValidPennyAmounts() {
    TransactionGenerator generator = new TransactionGenerator();

    for (int i = 0; i < 1000; i++) {
      var transaction = generator.generate(TransactionType.CREDIT);

      assertNotNull(transaction.id());
      assertEquals(2, transaction.amount().scale());
      assertTrue(transaction.amount().compareTo(new BigDecimal("200.00")) >= 0);
      assertTrue(
        transaction.amount().compareTo(new BigDecimal("500000.00")) <= 0
      );
    }
  }

  /** Checks both inclusive amount limits, debit/credit signs and preservation of the supplied ID. */
  @Test
  void includesBothAmountBoundariesAndAppliesSigns() {
    UUID id = UUID.randomUUID();
    TransactionGenerator minimum = new TransactionGenerator(
      () -> new BigDecimal("200.00"),
      () -> id
    );
    TransactionGenerator maximum = new TransactionGenerator(
      () -> new BigDecimal("500000.00"),
      () -> id
    );

    assertEquals(
      new BigDecimal("200.00"),
      minimum.generate(TransactionType.CREDIT).amount()
    );
    assertEquals(
      new BigDecimal("-200.00"),
      minimum.generate(TransactionType.DEBIT).amount()
    );
    assertEquals(
      new BigDecimal("500000.00"),
      maximum.generate(TransactionType.CREDIT).amount()
    );
    assertEquals(
      new BigDecimal("-500000.00"),
      maximum.generate(TransactionType.DEBIT).amount()
    );
    assertEquals(id, minimum.generate(TransactionType.CREDIT).id());
  }

  /** Rejects missing suppliers, transaction type, generated amount and generated ID. */
  @Test
  void rejectsNullDependenciesAndGeneratedValues() {
    assertThrows(NullPointerException.class, () ->
      new TransactionGenerator(null, UUID::randomUUID)
    );
    assertThrows(NullPointerException.class, () ->
      new TransactionGenerator(() -> BigDecimal.ONE, null)
    );
    assertThrows(NullPointerException.class, () ->
      new TransactionGenerator(() -> BigDecimal.ONE, UUID::randomUUID).generate(
        null
      )
    );
    assertThrows(NullPointerException.class, () ->
      new TransactionGenerator(() -> null, UUID::randomUUID).generate(
        TransactionType.CREDIT
      )
    );
    assertThrows(NullPointerException.class, () ->
      new TransactionGenerator(
        () -> new BigDecimal("200.00"),
        () -> null
      ).generate(TransactionType.CREDIT)
    );
  }

  /** Rejects values below or above the allowed range and values with sub-penny precision. */
  @Test
  void rejectsOutOfRangeGeneratedAmounts() {
    assertThrows(IllegalArgumentException.class, () ->
      new TransactionGenerator(
        () -> new BigDecimal("199.99"),
        UUID::randomUUID
      ).generate(TransactionType.CREDIT)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new TransactionGenerator(
        () -> new BigDecimal("500000.01"),
        UUID::randomUUID
      ).generate(TransactionType.DEBIT)
    );
    assertThrows(IllegalArgumentException.class, () ->
      new TransactionGenerator(
        () -> new BigDecimal("200.001"),
        UUID::randomUUID
      ).generate(TransactionType.CREDIT)
    );
  }
}
