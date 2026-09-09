# Exploration definitions

Exploration landmarks are server-owned data-pack content loaded from
`data/<namespace>/rovenfall/discoveries/*.json`. A reload validates the entire catalog before
replacing the active snapshot; one invalid or duplicate definition leaves the previous snapshot
active.

```json
{
  "title_translation_key": "discovery.rovenfall.hub_arrival",
  "description_translation_key": "discovery.rovenfall.hub_arrival.description",
  "version": 1,
  "dimension": "minecraft:overworld",
  "position": [0, 64, 0],
  "radius": 48,
  "public_guidance": true,
  "activity_xp": 10
}
```

- `title_translation_key` and `description_translation_key` are required localization keys.
- `version` is `1..1,000,000`. Increase it when the landmark's identity, area, or presentation
  changes. A player must enter the current area before an old receipt becomes current again, and
  the version change never grants the one-time reward twice.
- `dimension` must be the Hub (`minecraft:overworld`) or Wilderness
  (`rovenfall:wilderness`). Other dimensions reject the reload.
- `position` must be a spawnable block position. `radius` is an inclusive three-dimensional
  distance from that position and is limited to `1..64` blocks.
- `public_guidance` allows the title, description, region, and current-dimension waypoint to be
  shown before discovery. Set it to `false` for secret landmarks.
- `activity_xp` is optional. It grants `1..1,000,000,000` XP to the existing Exploration activity once.
  The captured reward is recovered from its persisted operation after a restart or definition
  reload; later definition edits cannot change a pending reward.

The catalog is limited to 128 definitions. Player state is limited to 256 versioned discovery
receipts. Detection uses only the server-observed player dimension and block position; the client
cannot submit a landmark identifier or coordinate.

## Privacy and guidance

An undiscovered private landmark becomes only an anonymous placeholder in the Journey journal.
Its identifier, localization keys, dimension, position, radius, version, and reward never enter
the presentation row or synchronized item lore. Public landmarks and current-version discovered
landmarks may be selected. The server then rechecks the active definition, catalog revision,
player receipt, and current dimension before sending a native locator-bar waypoint. Guidance does
not teleport the player, load a remote chunk, or reveal a marker in another dimension.
