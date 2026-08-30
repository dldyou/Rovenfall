# Operator release-candidate and recovery runbook

This runbook is the production checklist for a Rovenfall release candidate (RC) and for live
incident recovery. Run commands from the dedicated-server console unless a player operator is
explicitly required. Every mutation needs a fresh UUID transaction ID and a concise incident or
ticket reference in its reason. When the command accepts a transaction ID, retrying the same
operation uses that same ID; never generate a second ID to bypass a duplicate result.

## Roles and operator help

Run `/rovenfall admin help` to see the localized command groups allowed for the current role. The
same help is available in Korean, English, and Japanese.

| Role | Normal responsibility | Mutations allowed |
| --- | --- | --- |
| `VIEWER` | Health, audit, RPG, boss, and economy diagnostics | None |
| `MODERATOR` | Player safety and routine moderation | Claim overrides and RPG XP correction |
| `ECONOMY_MANAGER` | Economy and shop operations | Balance, shop, and supported transaction reversal |
| `CONTENT_MANAGER` | Content and progression operations | Portal definitions, promotion recovery, and skill reset |
| `OWNER` | Incident commander and release operator | All operations, including role assignment, protected regions, snapshots, Wilderness lifecycle, audit export, and boss recovery |

Native owner-level server permission is an audited emergency override. Assign a persistent role with
`/rovenfall admin role set <player> <role> <reason>`. Keep `OWNER` membership minimal.

## First response

1. Record the UTC time, reporter, affected player UUIDs, dimension/chunks, and visible transaction
   IDs. Do not copy chat, private messages, keystrokes, or unrelated player data.
2. Stop the affected activity if it can compound: close the shop, block portal traffic, or announce a
   maintenance window. Do not delete receipts or world directories.
3. Capture a read-only health snapshot with `/rovenfall admin operations [window_minutes]`. The
   default is 60 minutes and the accepted range is 1-1,440.
4. Correlate evidence:
   - `/rovenfall admin audit search <query>`
   - `/rovenfall admin audit search page <page> <query>`
   - `/rovenfall admin economy view transactions [page]`
   - `/rovenfall admin economy view alerts [page]`
   - `/rovenfall admin rpg history <player_uuid> [page]`
   - `/rovenfall admin rpg history suspicious <player_uuid> [page]`
   - `/rovenfall admin boss list [page]` and `/rovenfall admin boss mutations [page]`
5. Choose the narrowest owning-domain recovery below. If the evidence is ambiguous, take a platform
   snapshot and escalate before mutating anything.

Audit queries are AND-composed and bounded. An `OWNER` may export the exact bounded query with
`/rovenfall admin audit export <transaction_uuid> <reason> <query>`. The export is written only to
`<world>/rovenfall/exports/audit/audit-<transaction_uuid>.jsonl` through an atomic move. Treat it as
private operational evidence, share the minimum rows required, and delete external copies according
to the server's retention policy. The in-game audit remains append-only and retained for 30 days.

## Recovery decision table

| Symptom | Owning recovery | Required evidence | Failure/rollback |
| --- | --- | --- | --- |
| Incorrect standalone balance or supported shop transaction | Economy grant/debit or strict reversal | Original receipt, player UUID, item/stock state, before balance | No state changes on rejected/duplicate requests. Re-query the receipt; never invent a second transaction ID for a retry. |
| Incorrect claim permissions | Claim trust/untrust/settings correction | Dimension, chunk, owner/trust state, relevant payment receipt | Reapply the prior settings with a new audited correction if the first successful correction was wrong. |
| Incorrect activity XP | RPG XP add/remove | Player UUID, activity ID, provenance/history, expected delta | Retry the same transaction ID. Use platform restore only for broad correlated corruption. |
| Broken career promotion or skill allocation | Promotion recovery or branch/full skill reset | Player progression view, career/skill definitions, payment journal | Owning RPG protocol completes its journal or fails atomically. Do not reverse only the payment. |
| Stuck boss encounter or pending reward | Owner-only boss reset/recover | Encounter, participants, reward rows, cooldowns, mutation journal | Exact retries resume durable work. Never discard pending offline item delivery. |
| Broad permanent platform corruption | Platform snapshot restore | Snapshot ID, snapshot source hash, affected domain projections | Restore is a new audited operation. If verification fails, keep the current data authoritative and escalate. |
| Wilderness corruption or scheduled wipe | Restart-bound Wilderness reset/restore | Snapshot hash/count/bytes, warning ID, operation manifest | Startup restores the retired directory on apply failure and aborts on ambiguous filesystem state. Follow `docs/wilderness-reset.md`. |

### Economy and shop

- Credit: `/rovenfall admin economy grant <player> <amount> <transaction_uuid> <reason>`
- Debit: `/rovenfall admin economy debit <player> <amount> <transaction_uuid> <reason>`
- Reverse: `/rovenfall admin economy reverse <player> <original_transaction_uuid> <reversal_transaction_uuid> <decision> <reason>`

Use decision `strict` when the original granted items and stock can be reconciled exactly. Use
`refund_without_items_or_stock` only when an authorized compensating decision is documented; it is
never inferred. Account creation, claim purchase/sale, paid RPG skill operations, boss rewards, and
another reversal are intentionally unavailable through generic economy reversal because reversing
only the currency side would corrupt another domain. Use that domain's recovery protocol instead.

Shop definition mutations are `/rovenfall admin shop create|delete|bind|unbind|access|offer ...`.
Offer changes use the nested `offer set|remove|restock` commands. Before a
change, capture `/rovenfall admin economy view shops [page]`; after it, verify the offer, stock,
prices, receipt, and player inventory. A bad successful edit is corrected with a new transaction and
the prior values, never by editing history.

### Claims

Inspect with `/rovenfall admin claim info <dimension> <chunk_x> <chunk_z>`. Correct with:

- `/rovenfall admin claim trust <dimension> <chunk_x> <chunk_z> <player> <role>`
- `/rovenfall admin claim untrust <dimension> <chunk_x> <chunk_z> <player>`
- `/rovenfall admin claim settings <dimension> <chunk_x> <chunk_z> <entry_restricted> <public_interactions>`

Claim purchase and sale are cross-domain operations and cannot be undone by generic currency
reversal. Preserve the receipt, snapshot before bulk repair, and escalate for an owning-domain
compensation if the claim owner or purchase price is wrong.

### RPG

Inspect `/rovenfall admin rpg view <player_uuid> [page]`, `/rovenfall admin rpg config`, and the
history commands before changing progression.

- XP: `/rovenfall admin rpg xp add|remove <player_uuid> <activity_id> <amount> <transaction_uuid> <reason>`
- Promotion: `/rovenfall admin rpg promotion recover <player_uuid> <career_id> <transaction_uuid> <reason>`
- Skill branch: `/rovenfall admin rpg skill reset branch <player_uuid> <skill_id> <transaction_uuid> <reason>`
- Full career tree: `/rovenfall admin rpg skill reset full <player_uuid> <career_id> <transaction_uuid> <reason>`

Verify career tier/branch, XP, available skill points, learned skills, currency receipt, and journal.
Paid skill operations are deliberately non-reversible through the economy command.

### Bosses

Inspect `/rovenfall admin boss info <encounter_uuid>`, `participants`, `rewards`, and player
`cooldowns` before mutation. Only an `OWNER` may run:

- `/rovenfall admin boss reset <encounter_uuid> <transaction_uuid> <reason>`
- `/rovenfall admin boss recover <transaction_uuid> <reason>`

The request audit must be durable before encounter, entity, arena, or reward state changes. Verify
the related completion audit, encounter cleanup, every participant's currency/XP/item evidence, and
cooldown. Boss reward receipts are intentionally rejected by generic economy reversal.

### Platform and Wilderness snapshots

Create a platform snapshot with `/rovenfall admin snapshot create <reason>` before a broad repair,
bulk adjustment, migration, or restore. Restore only after freezing affected operations with
`/rovenfall admin snapshot restore <snapshot_uuid> <transaction_uuid> <reason>`. Reuse that
transaction UUID for an exact retry. Record and compare the source fixture hash before and after
rehearsal; source snapshots are immutable evidence and must never be edited.

Wilderness is restart-bound. Follow [Manual Wilderness reset and recovery](wilderness-reset.md) for
the warning, irreversible command, evacuation, manifest, server stop, and restart sequence. Never
copy player data, platform data, economy/claim data, or RPG data into a Wilderness snapshot.

## Definitions, configuration, and localization

Shipped economy, shop, activity, career, skill, mob, boss, protected-region, and portal definitions
are server data. Validate edits in a staging server, then run `/reload`. Definitions are parsed and
validated in isolation; one invalid reference, cycle, bound, or translation key rejects the entire
candidate and keeps the prior snapshot active. Confirm the reload result in all three locales and
open the relevant diagnostic view.

NeoForge server configuration files, including economy, claims, and `rovenfall-rpg-server.toml`, are
restart-bound operational inputs. Change them during a maintenance window, retain the prior file,
restart, and verify `/rovenfall admin rpg config` plus a health snapshot. A failed restart is rolled
back by restoring the prior config file, not by partially editing live SavedData.

## Release-candidate gate

Before the automated gate, complete the manual client matrix and attach real captures according
to [Custom UI release validation](ui-release-validation.md). Do not approve an RC with a clipped
action, unreachable keyboard control, missing narration, raw identifier in the normal view, or an
inventory-mod compatibility regression. The capture manifest must name the tested commit and may
not claim evidence that was not captured from a running client.

Use JDK 25. From a clean checkout of the intended `main` commit run:

```powershell
.\gradlew.bat clean build recoveryRehearsal --warning-mode all -Pmod_version=<MAJOR.MINOR.PATCH[-PRERELEASE]>
```

Linux CI uses the equivalent `./gradlew` command. This gate runs unit tests, all required GameTests,
the deterministic performance budget, the isolated non-destructive recovery rehearsal, and the mod
build. It must produce exactly:

- `build/libs/rovenfall-<version>.jar`
- `build/reports/performance-budget.md`

Generate and verify the checksum locally:

```powershell
Get-FileHash build/libs/rovenfall-<version>.jar -Algorithm SHA256
```

For a non-publishing GitHub validation, run the `Release` workflow manually with `version` entered
without the `v` prefix. Download the `rovenfall-<version>-release-candidate` artifact and verify its
JAR against the included `.sha256` file. Manual dispatch never creates a GitHub Release.

Public release is an explicit two-step owner action from a verified commit contained in `main`:

```powershell
git tag -a v<version> -m "Rovenfall <version>"
git push origin v<version>
```

Only an annotated SemVer `v*` tag starts publication. The workflow rebuilds on JDK 25, repeats the
complete gate, verifies the exact versioned JAR, writes its SHA-256 file, and creates or idempotently
updates the GitHub Release with both assets. If the tag is lightweight, not contained in `main`, the
gate fails, or a previously published asset has a different hash, publication stops. Do not move or
reuse a release tag; fix the cause and publish a new version.

## Evidence and escalation closure

An incident is closed only when the operator records the original evidence, chosen recovery,
transaction IDs, postcondition queries, snapshot/hash references, and any player communication.
Escalate to an `OWNER` when domains disagree, a persisted schema is read-only, a source hash changes,
a retry is not idempotent, a Wilderness manifest is ambiguous, or an artifact checksum differs.
Preserve the affected files and stop; do not improvise filesystem or NBT edits.
