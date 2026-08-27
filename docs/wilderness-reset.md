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
SHA-256 digest in the permanent overworld `rovenfall:platform` SavedData. At most eight snapshot
directories are accepted; reaching the limit fails before evacuation or world replacement so an
operator can archive old snapshots deliberately.

While an operation is staged, portal travel and Wilderness block, interaction, entity, fluid,
piston, fire, and explosion mutations are denied. Failure before the atomic manifest leaves the
current Wilderness authoritative and rolls back any partial evacuation. Failure while applying a
manifest restores the retired directory before the server loads levels; an unrecoverable filesystem
failure aborts startup for manual intervention rather than loading an ambiguous world.

The exchanged directory contains Wilderness blocks, containers, and entities only. Player files,
inventories, platform/economy/claim SavedData, and RPG SavedData remain in the permanent world root
or overworld storage and are not copied from a Wilderness snapshot.
