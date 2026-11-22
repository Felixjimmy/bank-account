# Bank account

Java 21 and Maven application for one GBP account. Two dedicated producer
threads target 25 credits and 25 debits per second. Each transaction has a UUID
and a signed amount between GBP 200.00 and GBP 500,000.00. The tracker updates
an exact `BigDecimal` balance and exposes `GET /balance`.

Every consecutive group of exactly 1,000 accepted transactions becomes one
audit submission. A worker packs it into batches capped at GBP 1,000,000 of
**absolute** transaction value and prints one JSON submission. Credits and
debits therefore use batch capacity equally. An incomplete group waits.

## Run and test

On Windows, from the project root:

```powershell
.\mvnw.cmd test
.\mvnw.cmd package
java -jar target/bank-account-0.1.0-SNAPSHOT.jar
```

Use `./mvnw test` and `./mvnw package` on Unix. Java 21 is required. The one
application starts producers, tracker, REST endpoint and audit worker. Open
[Swagger UI](http://localhost:8080/swagger-ui/index.html) or call `/balance`:

```json
{"currency":"GBP","balance":"-125.40"}
```

The required Java `BankAccountService.retrieveBalance()` returns `double`.
The REST response reads the exact decimal balance, preserving pennies. The
producers call the tracker in process; there is no transaction-ingestion API.

## Audit packing

The packer sorts absolute values from largest to smallest and uses integer
pennies for capacity comparisons. It calculates a proven lower bound from
total value and sorted-prefix item counts. Indexed best-fit decreasing and
first-fit decreasing each build a valid packing in O(n log n) time. When the
better count equals the lower bound, the minimum is proved immediately.

Otherwise iterative exact search tries smaller batch counts. It can prove a
minimum by finding a packing at the first feasible count or ruling out all
smaller counts. Search can take exponential time. Its default budget is **10
seconds**; on expiry the worker publishes its complete valid greedy packing,
marked `minimumProven=false` and `searchTimedOut=true`. It never publishes a
partial search assignment.

Each printed submission includes `submissionId`, `batchCount`,
`volumeLowerBound`, `minimumProven`, `searchTimedOut`, and per-batch
`totalValueOfAllTransactions` and `countOfTransactions`. The volume bound is
not an assumed optimum. See [algorithm evaluation](ALGORITHM_EVALUATION.md)
for solver comparisons, measurements and the timeout tradeoff.

Hard 1,000-item seed 6 has a proven lower bound of 244 and a valid greedy count
of 245; seed 9 has a bound of 240 and a count of 241. Fifteen-second exact
searches did not prove either minimum. The 10-second default leaves roughly
half of the nominal 20-second interval between groups at the target producer
rate. It is an operational compromise, not an optimality or latency guarantee.

## Configuration and lifecycle

Defaults in `src/main/resources/application.yaml` are:

```yaml
bank-account:
  audit:
    exact-search-timeout: 10s
    shutdown-timeout: 10s
  tracker:
    max-queued-audit-groups: 10
```

Override a setting with a Spring argument such as
`--bank-account.audit.exact-search-timeout=15s`. Timeouts and queue capacity
must be positive. The exact-search clock starts after greedy packing; sorting,
result construction and console output are outside its budget. Cooperative
deadline checks can slightly overshoot it.

The tracker owns the balance, current group and bounded queue. A full queue
pauses acceptance before the next complete group; waiting releases the tracker
lock, so balance reads remain available. The worker packs outside that lock.
On shutdown, producers stop and the worker gets up to 10 seconds to drain. It
reports in-flight, queued and partial work if draining fails. Search
interruption during shutdown cancels packing rather than publishing an
unproven result.

All pending work is in memory. A crash can lose it. Console output is not a
durable downstream acknowledgement; there is no database or remote retry.

## Verification and scope

The regular tests cover exact balances, producer behaviour, group boundaries,
queue pressure, HTTP responses, batch membership and capacity, independent
small-case optimality, timeout metadata, continued queue processing and
shutdown. The test-only helpers `AuditScale` and `DifficultAuditBenchmark`
support reproducible scale and hard-input checks; the application does not
start them.

After the 10-second change, 19 focused packer, worker and configuration tests
passed. A full run had 39 passes and two HTTP startup errors because the
execution environment denied loopback connections. An earlier full run under
the five-second default passed 41 tests. The latest run therefore does not
independently confirm the HTTP integration tests.

The application has one account, one audit worker and a bounded in-memory
queue. Spring Boot serves HTTP and manages lifecycle, springdoc provides
OpenAPI, Lombok provides logging and Spring test support covers integration.
Money and packing use the Java standard library. Solver libraries evaluated
during research are not production dependencies; review library credibility
and compatibility again when upgrading the versions in `pom.xml`.
