# Bank account: implemented design and remaining work

## Implemented

- Java 21 Maven and Spring Boot application for one GBP account.
- Two dedicated producers targeting 25 credits and 25 debits per second.
- One in-memory tracker implementing `BankAccountService`; it updates an exact
  `BigDecimal` balance and groups consecutive accepted transactions into
  submissions of exactly 1,000.
- Bounded completed-group queue (default 10). Producers wait before another
  full group would exceed capacity; balance reads remain available.
- `GET /balance` with a two-decimal GBP string, documented by OpenAPI.
- One audit worker packing each group into absolute-value-capped batches and
  printing a JSON submission.
- Indexed best-fit and first-fit decreasing, volume and prefix lower bounds,
  and bounded exact search when a gap remains. The default search budget is
  10 seconds. Timeout publishes a valid result with `minimumProven=false` and
  `searchTimedOut=true`.
- A separate 10-second shutdown drain deadline with unfinished-work reporting.
- Automated coverage for money, producer rate, concurrency, queue limits,
  HTTP, packing validity, small-case optimality, timeout and shutdown.

The [README](README.md) has run instructions and operational behaviour.
[Algorithm evaluation](ALGORITHM_EVALUATION.md) records the comparisons with
bin completion, integer solvers, column generation and local repair.

## Tradeoffs and remaining work

Greedy matching a proven lower bound gives an immediate optimality proof.
Exact search can resolve other gaps but can take exponential time. The
10-second limit prevents one hard group from blocking the worker without
bound; a timed-out result may cost an extra batch. Fifteen-second checks on
seeds 6 and 9 did not improve or prove their greedy results. The default
balances search opportunity against the nominal 20-second group arrival
interval; it is not an experimentally identified optimum.

The queue bounds memory but can slow producers if packing or console output
falls behind. Pending data exists only in memory. Restart recovery would need
persistent ledger and audit state, stable IDs, downstream acknowledgements
and retries. H2 is a possible future enhancement if recovery is required.
Multiple accounts, parallel audit workers and a remote audit server are not
part of the current implementation.

## Verification state

The full suite passed all 43 tests on 23 November 2026 with no failures,
errors or skipped tests, including both real-application integration tests.
The earlier environment-related HTTP errors are resolved.
The clean JaCoCo run measured 94.73% line coverage and 87.70% branch coverage.

Producer pipeline tests now assert each producer's rate independently:
25 transactions per second within 5%, measured over 20 seconds after a
two-second warm-up, with audit enabled and disabled. Every test method has a
comment explaining its purpose, including the opt-in latency benchmarks.
The latest combined rates were 49.823/s with audit and 49.591/s without audit.
The console-publication test waits for output before closing; separate worker
tests continue to check shutdown draining and deadline failures.

The README records the dependency assessment, supporting documentation and
the command for generating a fresh JaCoCo coverage report. The minimum-batch
qualification on exact-search timeout remains as described above.
