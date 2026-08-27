# Manual Wilderness reset and recovery

Wilderness reset is an owner-only, restart-bound operation. Rovenfall never hot-swaps a loaded
`ServerLevel`: it validates and snapshots the current Wilderness, evacuates its players to a safe
Hub position, writes an atomic operation manifest, and shuts the server down normally. On the next
start, NeoForge's pre-level-load lifecycle atomically exchanges the Wilderness directory before any
dimension is opened.

## Operator commands

1. Announce the irreversible loss with
   `/rovenfall admin wilderness reset warn <reason>`.
2. Within ten minutes, copy the returned warning UUID into
   `/rovenfall admin wilderness reset irreversible <warning_uuid> <transaction_uuid> <reason>`.
3. Restart the stopped server. Completion or safe failure is recorded in the audit log.

Restore a recorded snapshot with
`/rovenfall admin wilderness restore irreversible <snapshot_uuid> <transaction_uuid> <reason>`.
Restore also takes a new recovery snapshot of the current Wilderness before staging the selected
snapshot.

Only an administrator with the `OWNER` role or a native owner-level operator can run these commands.
Each reason is required, and transaction UUIDs make retries detectable.

## Safety and storage

Snapshots, staging data, retired worlds, and lifecycle manifests live under
`<world>/rovenfall/wilderness-resets`. Snapshot evidence records the file count, byte count, and
SHA-256 digest in the permanent overworld `rovenfall:platform` SavedData. Each snapshot also
captures Wilderness placed-resource provenance so restored ores cannot be treated as natural. At
most eight snapshot directories are accepted; reaching the limit fails before evacuation or world
replacement so an operator can archive old snapshots deliberately.

World-only snapshots created before provenance tracking are accepted only when their recorded hash
still matches. They are upgraded atomically with an empty Wilderness marker set, which is safe
because those snapshots predate placed-resource provenance.

While an operation is staged, portal travel and Wilderness block, interaction, entity, fluid,
piston, fire, and explosion mutations are denied. Preparation or evacuation failure leaves the
current Wilderness authoritative and rolls back any partial evacuation; a failed return teleport is
reported separately and never permits world replacement. The operation lock and evacuated player
positions are persisted before the atomic manifest is armed. If that persistence or manifest write
fails, the server stops without replacing the world and any persisted lock requires operator
intervention. Failure while applying a manifest restores the retired directory before the server
loads levels; an unrecoverable filesystem failure aborts startup for manual intervention rather
than loading an ambiguous world.

All managed paths are confined below the configured world root. Symbolic links, junction-like
reparse entries, conflicting or unreadable lifecycle manifests, and mismatched operation IDs abort
the lifecycle fail-closed. Startup removes UUID-named snapshot directories that were committed by a
crashed pre-commit attempt but never became referenced by permanent operation evidence.

The exchanged directory contains Wilderness blocks, containers, and entities only. Player files,
inventories, platform/economy/claim SavedData, and RPG SavedData remain in the permanent world root
or overworld storage and are not copied from a Wilderness snapshot.
