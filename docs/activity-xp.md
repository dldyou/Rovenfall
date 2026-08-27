# Authoritative activity XP

Activity XP is awarded only from NeoForge events observed on the dedicated
server. There is no client packet that can request an award. The seven initial
tracks use these completion seams:

- combat: positive applied health damage;
- cooking: a completed crafted or smelted food result;
- mining: a completed eligible ore break;
- exploration: the first server-earned advancement from the configured whitelist;
- hunting: recorded damage contribution when a non-player target dies;
- building: a completed placement by a builder on a retained claim; and
- farming: a mature crop break or completed breeding result.

`ActivityXpAwardService` is the only activity-XP mutation boundary. It validates
the activity definition, player and transaction UUIDs, amount, timestamp, and
source; rejects future read-only state; applies the configured per-award,
rolling-window, source cooldown, and per-target combat limits; then commits XP
and provenance atomically. Provenance contains a unique transaction UUID and is
available through the bounded, reverse-chronological `evidence` query.

The server configuration lives in `rovenfall-rpg-server.toml`. Operators can
change the maximum XP per result, maximum results per window, window duration,
source cooldown, combat XP cap per player and target, and exploration advancement
whitelist. These checks are
global across activity tracks so switching activities cannot bypass the rolling
limit. First exploration discoveries are retained separately from the bounded
audit trail, so revoking and re-granting an advancement cannot award XP twice.

Mining accepts the NeoForge common ore tag, not every pickaxe-minable block.
`rovenfall:activity_world_state` persistently records ore positions placed by
entities, including untrusted fake players. Breaking a recorded position
consumes the marker without awarding mining XP. A piston propagates the marker
conservatively, and future-schema or saturated tracking state disables mining
awards fail-closed. Wilderness reset must clear the known markers for the
regenerated dimension while permanent player XP remains in
`rovenfall:rpg_player_state`.

Protection is evaluated again at the actual event position before mining,
building, or farming awards. Fake players are never accepted as activity
actors. Combat contribution tracking is bounded and expires abandoned targets;
hunting credit is calculated from server-recorded participation instead of the
last damage source alone.
