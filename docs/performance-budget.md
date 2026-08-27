# Performance budget and 20–50 player validation

Run the repository-native gate with one command:

```text
./gradlew performanceBudget
```

On Windows, use `.\gradlew.bat performanceBudget`. The task runs deterministic 20- and 50-player
fixtures and writes `build/reports/performance-budget.md`. Each fixture awards currency, purchases
and checks a claim, awards RPG activity XP, travels through a durable portal, aggregates boss
contributions and mutation selection, queues recoverable boss rewards, and executes paged admin
reads. CI uploads the report and publishes it in the job summary.

## Structural budgets

| Surface | Budget | Enforcement |
| --- | ---: | --- |
| Target concurrent players | 20 and 50 | both fixture sizes must pass |
| Server-tick boss work | 32 active encounters; representative fan-out at 50 players | bounded encounter codec plus 50-player fixture |
| Admin query page | 50 rows | audit and economy observability APIs |
| Mutation admin scan/result | 50,000 scanned / 10,000 returned | explicit service caps |
| Audit ledger | 100,000 entries, 30 days | retention and bounded codec |
| Per-player economy rate index | 10,000 timestamps | bounded index and configuration validation |
| Claims / shop instances | 100,000 / 4,096 | bounded persistence codecs |
| Portal runtime entries | 100,000 | bounded persistence codec |
| RPG players / provenance per player | 100,000 / 256 | bounded persistence codecs |
| Boss reward recovery journal | 10,000 operations | bounded persistence codec and fixture |
| Wilderness recovery | 8 snapshots / 64 evidence records | bounded stores |
| Active-skill ingress | 20 requests per player per second | server rate limiter |
| Active-skill packets | 128-byte activation / 64-byte state sync | codec tests |

These exact bounds are merge gates. A changed bound requires a coordinated update to its production
constant, deterministic assertion, generated report, and this document. Hot event and tick handlers
must keep indexed lookups and bounded collections; a newly introduced full-state scan is a failure
even when a fast workstation completes it quickly.

## Timing evidence and baseline

The report records per-domain and total wall-clock measurements to make regressions visible. Those
numbers are diagnostics only and never fail CI: shared runners do not provide repeatable tick-time,
GC, CPU, or disk conditions. The initial software baseline is JDK 25, Gradle 9.7.1, NeoForge
26.2.0.66, and Minecraft 26.2. Hardware-specific evidence is established during release rehearsal.

For a release candidate, start a dedicated server with production-like view distance and datapacks,
then repeat the representative activity at 20 and 50 connected players. Capture the server's native
tick/profiler report, heap usage, GC pauses, and worst tick during steady-state windows. Archive that
evidence with the CI report and the distributable JAR from `build/libs`.

Re-baseline when the supported player target exceeds 50, a structural cap or packet shape changes,
the JDK/Gradle/NeoForge/Minecraft runtime changes, production hardware changes, a hot-path scan is
added, or native profiling shows a material tick-time/GC regression. The deterministic fixture is a
repeatable structural/load guard, not a substitute for real connected-player and world simulation.
