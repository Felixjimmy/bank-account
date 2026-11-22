# Audit packing: algorithm evaluation

The application packs each complete 1,000-transaction group into batches whose
absolute totals do not exceed GBP 1,000,000. All capacity checks use integer
pennies. Fewer batches cost less. This file records the relevant comparison of
methods; the operational design and commands are in [README.md](README.md).

## Current algorithm and proof status

The packer sorts transaction magnitudes in descending order, calculates a lower
bound from total magnitude and sorted-prefix cardinality, and constructs valid
best-fit and first-fit decreasing packings. Both indexed heuristics take
O(n log n) time. When the better packing matches the lower bound, it is proven
optimal. Otherwise iterative exact assignment search tries each smaller count
from the lower bound upward, grouping equal remaining capacities, checking
necessary capacity conditions, and retaining a bounded cache of failed states.
It can take exponential time.

Exact search has a configurable 10-second default budget. On timeout the worker
publishes the complete valid greedy packing with `minimumProven=false` and
`searchTimedOut=true`; the minimum may be smaller. The budget begins after
greedy packing and does not cover result construction or console publication.
The separate shutdown drain budget is 10 seconds. Pending work is in memory,
so a crash can lose it.

## Comparison and decisions

| Method | Observed result | Decision |
|---|---|---|
| Best-fit and first-fit decreasing | Fast valid arrangements; often match the lower bound and immediately prove optimality. Can miss a one-batch improvement. | Keep both. |
| Current capacity-based exact search | Proves difficult small and structured examples, but can run for minutes on hard random groups without finishing. | Keep as a bounded fallback. |
| Generic reordering and swap repair | Did not resolve the hard seeded groups within the measured budget. | Do not include in production. |
| Bin completion | Passed small and structured cases, but seeds 6 and 9 missed the 3-second process budget in all three samples each. | Do not include in production. |
| OR-Tools SCIP and CP-SAT | Proved small and structured 1,000-item cases; neither proved the hard seeds within a 10-second budget. CP-SAT returned unknown; SCIP returned a valid result with an open gap. | Do not add solver dependencies to production. |
| Column-generation lower bound | Certified bounds stayed at 244 and 240 for seeds 6 and 9 even after 60 seconds. Unfinished restricted LP values were not safe lower bounds. | Do not include in production. |
| Targeted batch emptying with moves and single swaps | More than 600,000 attempts per seed in 5 seconds; neither seed improved. | Do not include in production. |

All times above are local observations, not worst-case guarantees. The solver
experiments used different cold-start paths, so their elapsed times are not
controlled speedup ratios. A timed-out attempt does not prove infeasibility.

## Reproducible hard inputs and timeout decision

`DifficultAuditBenchmark.randomInput(6)` and `randomInput(9)` each generate 1,000
transactions with `java.util.Random(seed)`, uniformly sampled magnitudes from
20,000 to 50,000,000 pennies and alternating signs. Their true minima remain
unknown.

| Seed | Proven lower bound | Valid greedy count | 5-second exact search | 15-second exact search | 5-second rearrangement |
|---|---:|---:|---|---|---|
| 6 | 244 | 245 | 245, unproven | 245, unproven | 245 |
| 9 | 240 | 241 | 241, unproven | 241, unproven | 241 |

The 10-second default is a throughput compromise against the nominal 20-second
interval between 1,000-transaction groups at 50 transactions per second. The
5- and 15-second observations did not show a batch-count benefit on these two
seeds. They do not establish an optimal timeout for other inputs.

## Scale and verification

A historical single-run seeded uniform fixture reached the volume bound using
greedy packing: 1,000 transactions in 41 ms and 100,000 in 393 ms, excluding
any hard exact-search gap. The `AuditScale` test helper can reproduce a scale
measurement after test compilation. The regular test suite exercises exact
money, group boundaries, valid batch capacities, independent small-case
optimality, timeout metadata, continued queue processing, and shutdown.

The exercise asks for minimised batch count, but does not mandate a particular
solver or timeout. On a timed-out hard group, the application deliberately
reports an unproven valid arrangement rather than claiming a minimum.
