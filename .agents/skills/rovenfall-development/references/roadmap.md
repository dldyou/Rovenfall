# Delivery roadmap

Implement milestones in order unless the user explicitly reprioritizes them. Build narrow end-to-end slices inside each milestone; avoid scaffolding later milestones in advance.

## Milestone 0: trustworthy foundation

Status: complete on 2026-08-23. Reopen this milestone when a foundation version or build convention changes.

- Align Minecraft/NeoForge properties and metadata with 26.2.
- Remove obsolete Parchment configuration.
- Replace incompatible generated example code with the smallest valid 26.2 entry point.
- Establish `ko_kr`, `en_us`, and `ja_jp` catalogs.
- Make `.\gradlew.bat build` pass on JDK 25 and keep the wrapper complete.
- Add the smallest GameTest seam and persistence test harness needed by later domains.

Exit: a clean build produces the Rovenfall JAR, reloads in the IDE, and runs the empty mod without errors.

## Milestone 1: platform state and administration

Status: complete on 2026-08-23. Reopen this milestone when a platform persistence, definition reload, authorization, audit, snapshot, or administration-view contract changes.

- Stable IDs, versioned codecs/`SavedData`, migration registry, and snapshot/restore.
- Validated atomic data-definition reload.
- Administrator roles, permission-gated commands, transaction IDs, and structured audit storage/query.
- Read-only administration GUI shell with paginated queries.

Exit: a test definition and player record survive restart; invalid reload preserves the prior snapshot; role and audit tests pass.

## Milestone 2: economy and administrator shops

- Virtual currency accounts, configurable initial balance, and audited administrator/award/debit operations.
- Data-provided shop templates and operator-managed persistent instances with stable IDs, optional world binding, access policy, and audited entry create/edit/delete.
- Exact `ItemStack` offers with per-offer buy/sell price, finite or unlimited stock, and restock policy.
- Administrator commands and GUI queries for balances, transactions, shops, and stock.
- Anomaly alerts and targeted transaction reversal.

Exit: purchase and sale are atomic across currency, stock, inventory, persistence, and audit; exploit and overflow tests pass. A narrow purchase-only task can finish earlier when its own completion gate passes, but it does not complete this milestone until selling also passes.

## Milestone 3: claims, worlds, and portals

- Hub claim eligibility, arbitrary on-site chunk purchase, escalating price/cap, roles, flags, transfer, and sale.
- Full interaction protection matrix including explosions, pistons, fluids, fire, entities, and fake players.
- Permanent Hub and resettable Wilderness with protected portal rings.
- Administrator-managed extensible portals with safe arrival.
- Manual Wilderness reset with notices, evacuation, snapshot, restore evidence, and audit.

Exit: permission-matrix GameTests pass and Wilderness reset preserves global player/economy/RPG state.

## Milestone 4: activities, careers, and skill trees

- Seven activity tracks with provenance and anti-farming rules.
- Data-defined XP curves and arbitrary-tier acyclic branching careers.
- One active career, exclusive sibling branches, promotions, switching, and retained ancestors.
- Per-career skill points, passive/global rules, four active slots, keybind packets, cooldown validation, and paid reset.

Exit: progression, promotion, branch switching, skill use, restart, malformed packets, and audit are covered automatically.

## Milestone 5: mobs, mutations, and boss encounters

- Two ordinary custom mobs.
- Data-composed Wilderness mutations for eligible existing mobs.
- One protected-arena boss with multiple observable patterns.
- Contribution-based personal rewards, reward cooldowns, and lifecycle audit.

Exit: spawn boundaries, mutation composition, boss state transitions, reward eligibility, restart behavior, and no-Hub-spawn rules pass.

## Milestone 6: operations hardening

- Complete searchable admin views and rate-based anomaly alerts.
- Targeted domain reversals and rehearsed snapshot restore.
- Performance profiling at the target 20–50 concurrent-player scale.
- Balance data, operator help, and release validation.

Exit: the full completion gate in `SKILL.md` passes on a release candidate and the artifact under `build/libs` is ready for the dedicated server and modded clients.

## Deferred until requested

- player-owned shops and direct player transfers;
- external database or multi-server synchronization;
- external web or Discord administration dashboard;
- automatic punishments;
- player-created portals;
- automatic claim repossession;
- general block-history rollback;
- mana or stamina; and
- arbitrary skill scripting.
