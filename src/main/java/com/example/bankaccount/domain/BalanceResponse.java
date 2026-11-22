package com.example.bankaccount.domain;

import io.swagger.v3.oas.annotations.media.Schema;

public record BalanceResponse(
  @Schema(description = "Account currency", example = "GBP") String currency,
  @Schema(
    description = "Signed balance in pounds with two decimal places",
    example = "-125.40"
  )
  String balance
) {}
