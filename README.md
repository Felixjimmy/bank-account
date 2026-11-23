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

The full suite passed all 43 tests on 23 November 2026, with no failures,
errors or skipped tests, including the real HTTP and console-publication
integration tests. The earlier environment-related HTTP errors are resolved.
The clean JaCoCo run measured **94.73% line coverage** and **87.70% branch
coverage** of production code.

Both producer pipeline tests measure a 20-second window after a two-second
warm-up, with audit processing enabled and disabled. They independently assert
25 credits and 25 debits per second within a 5% tolerance (23.75 to 26.25 each),
using actual elapsed time. This allows ordinary scheduling variation on a
shared host while detecting sustained rate regressions or one producer masking
the other's slowdown. It is not a hard real-time or overloaded-host guarantee.

| Latest measured rate (transactions/second) | Credits | Debits | Combined |
|---|---:|---:|---:|
| Audit enabled | 24.886 | 24.936 | 49.823 |
| Audit disabled | 24.796 | 24.796 | 49.591 |

The console-publication test waits for its expected submissions before closing
the context, so random startup transactions cannot race the shutdown deadline.
Separate worker tests verify shutdown draining and deadline failures.

To reproduce coverage without changing the build configuration:

```powershell
.\mvnw.cmd clean org.jacoco:jacoco-maven-plugin:0.8.14:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.14:report
```

Open `target/site/jacoco/index.html`. The opt-in latency benchmarks are outside
the regular 43-test suite. All test methods have comments explaining their
purpose.

The application has one account, one audit worker and a bounded in-memory
queue. Money and packing use the Java standard library. Solver libraries
evaluated during research are not production dependencies.

## Dependency assessment

Dependencies were selected for documented behaviour, compatibility with the
build and a limited role in the application. Versions are pinned explicitly or
managed by the Spring Boot parent in `pom.xml`.

| Dependency | Reason for selection and evidence | Tradeoff and verification |
|---|---|---|
| Spring Boot 4.2.0 / Web MVC | Provides HTTP serving and lifecycle management. Its [documented dependency management](https://docs.spring.io/spring-boot/reference/using/build-systems.html) supplies a curated set of supported versions, reducing manual version mismatches. | Adds framework and transitive dependency overhead. Real application tests exercise startup, HTTP responses and shutdown; reassess compatibility when upgrading the parent. |
| springdoc-openapi 3.2.1 | Its [project documentation](https://springdoc.org/) describes generation of OpenAPI and Swagger UI for Spring applications, avoiding a separate hand-maintained API schema. | Adds endpoints and a separately versioned integration. The HTTP test checks the generated balance schema and Swagger UI with the pinned versions. |
| Lombok, parent-managed | The [documented logging annotation](https://projectlombok.org/features/log) generates a conventional logger field. Usage is limited to logging rather than money or packing behaviour. | Requires annotation processing and compiler compatibility. Compilation verifies generation; explicit logger fields remain a straightforward replacement. |
| Spring Boot test starter, parent-managed and test-scoped | The [official testing documentation](https://docs.spring.io/spring-boot/reference/testing/index.html) describes its integration support and JUnit Jupiter assertions. It lets tests exercise real context wiring as well as isolated domain logic. | Test libraries do not form part of the application's runtime dependencies. Real-thread timing tests need scheduling tolerance; independent exhaustive packing checks reduce reliance on implementation-specific expectations. |

These are suitability and compatibility assessments, not a claim that any
dependency is vulnerability-free. Review release notes, advisories and
compatibility when changing versions, then rerun the application tests.
