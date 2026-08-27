# RPG player state

RPG runtime progression is stored in the permanent overworld data storage under
`rovenfall:rpg_player_state`. It is intentionally separate from the platform and
economy root so a future wilderness reset cannot remove progression.

The root is schema version 2 and contains at most 100,000 UUID-keyed player
records. Each record stores activity XP, learned career progress, the active
career, four indexed active skill slots, skill cooldown deadlines, the last
processed active-skill request number, and a bounded
set of first exploration discoveries plus separate bounded activity and career
provenance trails with globally unique transaction UUIDs. IDs are namespaced resource IDs;
player names are never keys.

All persisted maps use sorted entry lists. This gives deterministic NBT output
while enforcing limits during decoding. XP, ranks, points, cooldowns, source
text, and provenance are bounded before a mutation is committed. A failed
operation leaves the previous state untouched and only a committed operation
marks the SavedData dirty.

Schema migrations are explicit. Schema 0 is promoted through schema 1 to schema 2 without
inventing player records. A newer or invalid schema remains readable but is
marked read-only; the RPG service rejects mutations until the server supports
that schema. Unknown definition IDs are preserved by the codec and are not
silently discarded; gameplay mutations that need a missing definition fail
closed.

Authoritative activity awards use this storage contract as described in
[`activity-xp.md`](activity-xp.md). Careers form a validated acyclic graph:
tier-one careers are roots, every later tier declares one or more lower-tier
parents, and promotion requires every parent at its configured maximum rank plus
all activity-level requirements. The tier limit is a safety bound rather than a
hard-coded three-stage progression. The switching policy is explicit: any
previously promoted career, including sibling branches and ancestors, may become
active again, while an unpromoted definition cannot be selected. Switching
changes no accumulated progress. Promotion and
switching each append provenance, including the previous and new active career,
in the same atomic player-state commit. `CareerProgressionService.history` is the
bounded authoritative query for operator-facing career audit views; Milestone 4's
RPG administration view consumes this trail rather than the separate platform
configuration audit root.

Each career rank gained from authoritative activity XP grants one skill point.
`RpgSkillService` spends points only after validating the owning promoted career,
the requested skill rank, and every prerequisite across the complete career graph.
Definitions form a validated prerequisite DAG. Passive effect metadata is data-driven;
the initial effects modify dealt damage or reduce incoming damage, and only learned
passives in the active career and its ancestors apply on the server.

Active skills use a strict versioned client-intent protocol. On login the server
sends the current validated-definition revision, enabled slot count, next request
number, and a fresh session nonce. A key press sends only that envelope, the slot,
the actor's claimed dimension, and an optional targeted entity ID. The server
resolves the bound definition and target, then validates the learned rank, active
career lineage, definition revision, dimension, range, line of sight, claim
permission, cooldown, request sequence, and rate limit. Damage, duration, range,
and cooldown values always come from the installed definition. A processed
request number and successful cooldown are committed together, so replay remains
rejected after restart. Cooldown deadlines use the persistent server world game
time. The initial per-cast cost is cooldown-only; skill points remain a learning
cost, not a client-supplied activation cost. Short-lived effects are discarded
when the player logs out, changes career, resets skills, or definitions reload,
so an effect cannot outlive the state and definition revision that authorized it.

Branch reset removes the selected learned skill and every transitive learned
dependent. Full reset starts with every learned skill owned by the selected career
and then applies the same dependent closure, so no surviving skill has a dangling
prerequisite. The exact removal/refund plan is validated before any payment.
`RpgSkillPaymentService` atomically debits the economy root and stores that plan as
a pending platform operation; `RpgSkillService` then commits the reset and its RPG
provenance, after which the platform operation is completed. A login recovery pass
finishes either save-order interruption using the same transaction UUID. The
dedicated `rpg_skill_payment` receipt is never accepted by generic economy reversal.
Branch and full prices are configurable in `rovenfall-rpg-server.toml`.

RPG administration uses `/rovenfall admin rpg`. Every administrator role may
page through an offline UUID's progression, recent award evidence, suspicious
award-only evidence, and the effective configuration/definition revision.
`MODERATOR` and `OWNER` may adjust activity XP. `CONTENT_MANAGER` and `OWNER`
may recover a promotion whose activity progress evidence was lost and perform a
no-charge support skill reset. Promotion recovery still requires every parent
career at its configured maximum rank, and lowering activity XP
does not silently revoke career XP, ranks, points, or learned skills.
The concrete subcommands are `view <player_uuid> [page]`,
`history <player_uuid> [page]`, `history suspicious <player_uuid> [page]`,
`history activity <player_uuid> <activity> [page]`, `config`,
`xp add|remove <player_uuid> <activity> <amount> <transaction_uuid> <reason>`,
`promotion recover <player_uuid> <career> <transaction_uuid> <reason>`, and
`skill reset branch|full <player_uuid> <target> <transaction_uuid> <reason>`.

Every administrative mutation requires an explicit reason and transaction UUID.
The platform schema 12 `RpgAdminOperation` journal first stores the canonical
actor, player, action, target, expected value/delta or exact reset plan as
`PENDING`. The RPG root then applies the same transaction idempotently and writes
admin provenance. Finally the platform changes the operation to `COMPLETED` and
appends an `AuditEntry` containing actor, target, before/after, reason, and
transaction UUID in one platform commit. Login recovery completes either save
ordering without repeating the mutation. A player cannot start another admin RPG
mutation while a prior one is pending.

Player commands are `/rovenfall skill learn <skill>`,
`/rovenfall skill bind <1..4> <skill>`, `/rovenfall skill unbind <1..4>`,
`/rovenfall skill reset branch <skill>`, and
`/rovenfall skill reset full <career>`. Active slots use configurable Z/X/C/V
key mappings and the server may enable one to four slots. `promotion_cost` remains definition metadata until career
promotion receives its own cross-root payment policy. The `Snapshot` view is
immutable and suitable for administration queries or background read-only work.
`RpgPlayerSnapshotStore` can persist an atomic compressed NBT copy under
`rovenfall/snapshots/rpg`; it refuses overwrite, size violations, malformed data,
and unsupported schemas.
