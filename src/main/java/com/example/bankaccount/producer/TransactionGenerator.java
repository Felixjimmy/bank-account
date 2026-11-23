package com.example.bankaccount.producer;

import com.example.bankaccount.domain.Transaction;
import com.example.bankaccount.domain.TransactionType;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class TransactionGenerator {

  private static final BigDecimal MIN_AMOUNT = new BigDecimal("200.00");
  private static final BigDecimal MAX_AMOUNT = new BigDecimal("500000.00");

  private final Supplier<BigDecimal> magnitude;
  private final Supplier<UUID> ids;

  /** Creates a generator using random penny amounts and UUIDs. */
  public TransactionGenerator() {
    // Sample a whole-penny position uniformly, then use BigDecimal for money.
    this(
      () ->
        BigDecimal.valueOf(
          ThreadLocalRandom.current().nextLong(20000, 50000001),
          2
        ),
      UUID::randomUUID
    );
  }

  /** Creates a generator with injectable amount and ID sources for controlled use and tests. */
  public TransactionGenerator(
    Supplier<BigDecimal> magnitude,
    Supplier<UUID> ids
  ) {
    this.magnitude = Objects.requireNonNull(magnitude);
    this.ids = Objects.requireNonNull(ids);
  }

  /** Generates a validated transaction whose sign is determined by its type. */
  public Transaction generate(TransactionType type) {
    Objects.requireNonNull(type, "type");
    BigDecimal amount = Objects.requireNonNull(magnitude.get(), "magnitude");
    if (
      amount.scale() > 2 ||
      amount.compareTo(MIN_AMOUNT) < 0 ||
      amount.compareTo(MAX_AMOUNT) > 0
    ) {
      log.warn(
        "Rejected generated amount {} GBP: amounts must have at most two decimal places and be between {} and {}",
        amount,
        MIN_AMOUNT,
        MAX_AMOUNT
      );
      throw new IllegalArgumentException(
        "Generated amount outside GBP 200.00 to GBP 500000.00"
      );
    }
    UUID id = ids.get();
    Transaction transaction = new Transaction(
      id,
      type == TransactionType.DEBIT ? amount.negate() : amount
    );
    log.debug(
      "Generated {} transaction {} for GBP {}",
      type,
      transaction.id(),
      transaction.amount()
    );
    return transaction;
  }
}
