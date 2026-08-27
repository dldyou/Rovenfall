# RPG extension contracts

RPG content is extended through validated data definitions and narrow
server-side adapters. Stable namespaced IDs are the contract between definitions,
saved player state, commands, audit evidence, and clients; translated text is not
an identifier.

## Add an activity

1. Add `data/rovenfall/rovenfall/activities/<id>.json` with a translation key and
   strictly increasing `level_xp` thresholds.
2. Add the translation key to `en_us`, `ko_kr`, and `ja_jp`.
3. If the activity observes a new kind of gameplay, add the smallest event adapter
   in `RpgActivityEvents`. The adapter validates a completed server-observed outcome
   and delegates the award to `ActivityXpAwardService`; it does not edit player
   state directly.
4. Give each award a stable source and a unique transaction UUID. Preserve the
   global window, per-source cooldown, combat cap, fake-player, protected-region,
   and repeat-placement policies.

Adding a data definition alone is sufficient when an existing adapter already
emits that activity ID.

## Add a career tier or branch

Add `data/rovenfall/rovenfall/careers/<id>.json`. Tier 1 careers have no parent;
every higher tier declares one or more lower-tier parents. The graph must be
acyclic. `CareerProgressionService` supports arbitrary validated tier numbers and
sibling branches without a hard-coded final tier.

Promotion requires every parent at its configured maximum rank and every declared
activity level. `level_xp` controls rank progression, `career_xp_multiplier`
converts activity awards while the career is active, and each gained rank grants
one skill point. `promotion_cost` remains definition metadata until a dedicated
cross-root payment policy is introduced; content code must not debit currency for
it ad hoc.

Previously promoted careers remain selectable. New branch rules must preserve
progress in sibling and ancestor records.

## Add a skill

Add `data/rovenfall/rovenfall/skills/<id>.json` and all three locale entries. A
skill belongs to one career and its prerequisites must be inside that career's
ancestry. `max_rank` and `point_cost` are server-owned.

- Passive skills declare one typed `passive_effect`. Evaluation is derived from
  learned skills in the active career lineage by `RpgPassiveSkillService`.
- Active skills declare `cooldown_ticks` and one typed `active_effect`.
  `RpgActiveSkillService` validates the definition revision, monotonic request,
  learned slot, active lineage, cooldown, dimension, and server-resolved target.
  The runtime gateway applies the effect only after validation.

Per-cast active-skill cost is cooldown-only in the current contract. A new mana,
stamina, item, or currency cost requires an explicit registered resource type and
an atomic reserve/commit policy; it is not implemented as an arbitrary economy
write inside an effect.

Adding a new effect type is a code extension: update the typed definition codec,
validator, evaluator or gateway, all three locale catalogs when visible text is
added, and focused tests. Do not encode executable class names or scripts in data.

## Reload, persistence, and cross-root mutations

`RpgDefinitionReloadListener` prepares a complete `RpgDefinitionSnapshot` before
publishing it. Duplicate IDs, missing references, cycles, invalid lineage,
impossible prerequisites, unsafe bounds, or invalid translation keys reject the
whole candidate and retain the last good snapshot and revision. Active-skill
requests carry that revision and stale requests fail closed.

Definition reload never rewrites `RpgPlayerSavedData`. Removed IDs remain visible
as unresolved persisted IDs until an explicit migration or replacement policy is
shipped. Adding a persisted player field requires a schema migration, codec
round-trip fixture, size bound, and newer-schema read-only behavior.

Paid skill resets use `RpgSkillResetCoordinator`: persist the exact dependency
closure and economy payment as pending, apply the RPG mutation with the same
transaction UUID, then complete the platform operation. Generic economy reversal
must not reverse this receipt. Administrator corrections use
`RpgAdministrationService` and its separate durable journal. No extension may
write both persistence roots directly.

## Required verification

`RpgMilestoneWorkflowTest` is the connected milestone gate. It covers activity
and career XP, arbitrary branching promotion, point spending, passive and active
effects, a paid dependency reset, codec persistence, definition reload, idempotent
retry, and pending-operation recovery.

The dedicated-server GameTests retain the runtime boundaries:

- `rovenfall:rpg_definitions` checks shipped data and typed effects;
- `rovenfall:rpg_activity_xp` checks actual NeoForge event adapters and persistence;
- `rovenfall:rpg_active_skill` checks entity targeting, line of sight, cooldown,
  duration, and server effect application.

Every RPG extension finishes with focused unit tests, the full JUnit suite,
localization parity, all required GameTests, and a clean JDK 25 build. The release
artifact is the Rovenfall JAR under `build/libs`.
