# RPG player state

RPG runtime progression is stored in the permanent overworld data storage under
`rovenfall:rpg_player_state`. It is intentionally separate from the platform and
economy root so a future wilderness reset cannot remove progression.

The root is schema version 1 and contains at most 100,000 UUID-keyed player
records. Each record stores activity XP, learned career progress, the active
career, four indexed active skill slots, skill cooldown deadlines, and a bounded
set of first exploration discoveries plus separate bounded activity and career
provenance trails with globally unique transaction UUIDs. IDs are namespaced resource IDs;
player names are never keys.

All persisted maps use sorted entry lists. This gives deterministic NBT output
while enforcing limits during decoding. XP, ranks, points, cooldowns, source
text, and provenance are bounded before a mutation is committed. A failed
operation leaves the previous state untouched and only a committed operation
marks the SavedData dirty.

Schema migrations are explicit. Schema 0 is promoted to schema 1 without
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

`promotion_cost` remains definition metadata until an economy/RPG transaction
coordinator can debit and promote atomically across their separate persistence
roots. Skill learning and active-skill activation are introduced by their own
milestone issues. The `Snapshot` view is
immutable and suitable for administration queries or background read-only work.
`RpgPlayerSnapshotStore` can persist an atomic compressed NBT copy under
`rovenfall/snapshots/rpg`; it refuses overwrite, size violations, malformed data,
and unsupported schemas.
