package com.example.bankaccount.tracker;

import com.example.bankaccount.domain.BalanceResponse;
import io.swagger.v3.oas.annotations.Operation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public final class BalanceController {

  private final InMemoryBalanceTracker tracker;

  /** Creates the REST controller backed by the in-memory balance tracker. */
  public BalanceController(InMemoryBalanceTracker tracker) {
    this.tracker = tracker;
  }

  /** Returns the exact current balance as a GBP decimal string. */
  @GetMapping("/balance")
  @Operation(
    summary = "Retrieve the current GBP balance",
    description = "Returns the balance of accepted transactions, including negative balances. " +
      "The decimal string preserves exact penny precision."
  )
  public BalanceResponse balance() {
    log.debug("Retrieving account balance");
    return new BalanceResponse("GBP", tracker.exactBalance().toPlainString());
  }
}
