# Implementation invariants

These are contracts. A change that violates one needs an explicit product decision and a coordinated update to this reference, affected migrations, and tests.

## Authority and validation

- The server owns balances, prices, stock, claims, permissions, experience, careers, skills, cooldowns, portals, spawns, loot, and rewards.
- Clients send intent only. Validate packet type, size, count, namespaced IDs, actor, dimension, position, distance, target, permission, and rate before use.
- Use a strict Rovenfall network protocol version initially. Reject unknown, malformed, replayed, or incompatible requests without mutation.
- Run gameplay mutations on the server thread. Background work may parse immutable input or write prepared snapshots, but it cannot touch live Minecraft state.

## Ownership and boundaries

- Key players by UUID, chunks by dimension plus coordinates, and definitions by stable namespaced ID.
- Each domain service is the only writer of its state. Cross-domain code calls operations; it never edits another domain's collections or `SavedData` directly.
- Keep definitions, runtime state, and presentation separate. Data files define content and balance; versioned `SavedData` owns mutable state; translation files own visible text.

## Atomic operations

Validate the whole operation before committing. On any failure, preserve all prior state.

Required atomic operations include:

- shop purchase/sale: authorization, access policy/proximity, exact offer components, offer-unit quantity, server price, balance, stock, inventory capacity, mutation, audit;
- shop catalog mutation: administrator role, shop/entry ID, exact item validity, prices, stock policy, dependent operation lock, mutation, audit;
- claim purchase: eligible chunk, protection override, ownership cap, balance, debit, ownership, audit;
- promotion: active lineage, prerequisites, inventory/currency costs, branch conflict, mutation, audit;
- skill unlock/reset: tree version, prerequisites, points/currency, mutation, audit;
- active skill: protocol/session, monotonic request, definition revision, learned active slot, current career lineage, persistent world-time cooldown, server-resolved target/dimension/range/line of sight/protection, effect, and request/cooldown commit; discard armed effects on logout, career change, skill reset, or definition reload;
- RPG administration: viewer-or-higher read authorization; moderator/owner activity XP adjustment; content-manager/owner career recovery and support skill reset; actor, offline target UUID, bounded reason, transaction UUID, canonical before/after or exact reset plan, pending platform journal, idempotent RPG mutation and provenance, completed platform journal plus audit; recover pending operations before accepting another mutation for that player;
- portal travel: portal link, cooldown, combat rule, safe destination, teleport, audit where required; and
- administrative mutation: role, reason, target snapshot, mutation, audit.

Use `long` for currency and experience totals. Check addition, multiplication, conversion, negative values, and configured upper bounds before mutation. Assign a unique transaction ID to retryable or multi-domain operations so a retry cannot charge or reward twice.

Paid skill resets cross the Platform and RPG persistence roots. Persist the exact validated reset plan with a non-reversible `RPG_SKILL_PAYMENT` receipt as `PENDING`, commit the RPG reset with the same transaction UUID, then mark the operation `COMPLETED`. Login recovery must handle either root reaching disk first without charging or refunding twice. Before applying a persisted plan, regenerate it from the current definitions and player state and require an exact match. Missing/mismatched receipts make Platform persistence read-only; a pending receipt never expires before completion.

Administrative RPG mutations use the same cross-root ordering without an economy receipt: persist a canonical `RpgAdminOperation` as `PENDING`, apply the RPG change and admin provenance idempotently, then complete the journal and platform audit together. Support resets never debit player currency. Promotion recovery may bypass activity thresholds, but every parent career must already be at its configured maximum rank. Activity XP reductions never cascade into implicit career or skill revocation.

## Persistence and migration

- Store global player/economy/career state outside the resettable Wilderness dimension.
- Give every persisted root a schema version. Load through explicit migrations and test representative older fixtures.
- Never silently discard an unknown or removed definition ID. Preserve it as unresolved, map it through an explicit replacement, or fail the administrative migration with evidence.
- Definitions in use are deprecated before deletion. Destructive cleanup requires a migration and snapshot.
- Mark data dirty only after a committed mutation. A failed load keeps the last valid state and blocks unsafe operations.

## Definitions and reload

- Parse all candidate definitions into an isolated snapshot.
- Validate duplicate IDs, missing references, career cycles, skill cycles, invalid ranks, impossible prerequisites, invalid prices/stocks, portal links, translation keys, and numeric bounds.
- Swap the complete validated snapshot atomically. A single error keeps the prior snapshot and reports file, definition ID, and cause.
- Balance and content values belong in data unless they enforce a safety invariant.
- Extend activities, arbitrary career tiers/branches, and skills through stable namespaced data IDs and the typed definition snapshot. Event adapters only validate completed server outcomes and delegate to domain services; new effect or resource-cost types require an explicit code contract and milestone-gate coverage.

## Authorization and protection

- Resolve administrator protection before claim or public flags. Resolve claim roles before a requested interaction.
- Check the actual affected positions for explosions, pistons, fluids, fire, vehicles, entities, and multi-block actions; checking only the initiating block is insufficient.
- Cancel unauthorized events before state changes and item consumption. Record security-relevant denied attempts with rate limiting.
- Fake players and automation have no implicit trust. They require an explicit compatible policy per operation.

## Experience and rewards

- Reward completed server-observed outcomes, not client reports or merely attempted/cancelled events.
- Track provenance where repeat placement would create XP loops. Player-placed ore cannot become natural ore; immature or instantly replaced crops do not award farming XP.
- Cap combat credit per target and use contribution for hunting/boss rewards. Last hit alone is insufficient.
- Apply configurable time/rate ceilings and alert on anomalies. Alerts never punish automatically.

## Audit, monitoring, and recovery

An audit entry contains timestamp, actor UUID, action type, target, dimension/position when relevant, before value, after value, reason, and transaction ID.

Audit at minimum:

- currency and stock changes;
- shop, claim, trust, career, skill, portal, and configuration changes;
- Wilderness reset and restoration;
- boss lifecycle and rewards;
- administrator commands; and
- rate-limited denied protected actions and malformed requests.

Keep audit entries append-only for ordinary administrators, searchable, paginated, and retained for 30 days with rotation. Log mod-relevant state only; never collect chat, private messages, or key input. Monitoring thresholds produce GUI and console alerts, not automatic sanctions.

Support targeted reversal for economy, shops, claims, permissions, careers, and skills. A reversal is a new authorized transaction referencing the original; it never erases history. A shop-purchase reversal reclaims the exact granted items only when they remain available; otherwise it requires an explicit compensating administrator decision and records that decision. It never silently duplicates currency or deletes unrelated items. Use snapshots rather than a general block-history engine for Wilderness reset and bulk migrations.

Generic economy reversal must reject cross-domain payments such as a paid skill reset. Reversing one side without its owning domain's compensation protocol is state corruption, not recovery.

Create a snapshot before Wilderness reset, bulk economy adjustment, destructive migration, or restore. Evacuate players and block concurrent affected operations during reset/restore.

Boss administration is `OWNER`-only for mutation. Persist the reset/recovery request audit before touching encounter, entity, arena, or reward state; record completion under a deterministic related transaction only after postconditions are verified. Retain `REWARD_PENDING` encounters until contribution, loot, currency, XP, item, and cooldown evidence is durable. Offline item delivery remains pending and recoverable; it is never treated as disposable stuck state. Exact transaction retries resume pending work without duplicating rewards or cleanup.

## Performance

- Resolve claims by dimension/chunk index, players by UUID, and shops/portals by ID. Never scan every claim or player in a block, tick, packet, or interaction handler.
- Paginate admin queries and compute expensive aggregates outside hot event paths from immutable snapshots.
- Avoid per-tick work when an event, scheduled batch, or cached deadline provides the same behavior.
- Bound packet collections, search radii, mutation candidates, audit query windows, and definition counts.

## Localization

- No user-visible prose in Java or mutable data. Send translation keys and safe parameters.
- Keep `ko_kr`, `en_us`, and `ja_jp` key sets equal. Add all three translations in the feature change.
- Namespaced IDs, commands, save keys, and audit action types are stable technical identifiers and are never translated.

## Verification

For each state-changing feature, cover:

- successful operation;
- every authorization role that changes the result;
- invalid/overflow/insufficient input;
- retry or duplicate delivery where applicable;
- save/load round trip and migration when applicable;
- atomic failure with no partial state;
- audit entry contents; and
- relevant localized error keys.

Finish with the focused tests, relevant GameTests, and `gradlew build` on JDK 25. Verify the distributable Rovenfall JAR in `build/libs`, not merely the Minecraft development artifacts.
