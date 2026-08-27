# Operations metrics

Run `/rovenfall admin operations [window_minutes]` to build a read-only snapshot. The default window is 60 minutes; accepted values are 1–1,440 minutes. Player operators receive a three-page book and consoles receive the same localized summary. A warning points operators to the existing audit, economy alert, RPG history, and boss reward views. Warnings never punish or mutate players.

| Metric | Owning evidence | Window | Persisted/query cap |
| --- | --- | --- | --- |
| Economy transactions | `PlatformSavedData` economy receipts, unique transaction ID | Requested window | 250,000 persisted receipts |
| Amount/rate alerts | `PlatformSavedData` economy alerts, unique transaction ID and alert type | Requested window | 10,000 persisted alerts |
| Denied/malformed requests | Append-only audit action and transaction ID | Requested window | 100,000 retained audit entries |
| Suspicious RPG awards | RPG activity-XP provenance and configured award/rate/cooldown/source caps | Requested window | First 50 player UUIDs, 256 provenance entries each |
| Active boss encounters | `BossEncounterSavedData` | Point-in-time | 32 encounters |
| Pending rewards | Boss reward operations in `PENDING` | Point-in-time | 10,000 operations |
| Pending recovery | Boss rewards in `CORE_APPLIED`, pending RPG journals, and an active Wilderness operation | Point-in-time | Existing owning-domain journal caps |

The book records its freshness timestamp, whether the RPG player scan was truncated, and up to five anomaly transaction IDs that can be opened in the owning audit/history view. The calculation consumes immutable projections, deduplicates transaction evidence, and remains available when a future loaded schema is read-only.
