# Quest definition contract

Quest content lives in `data/<namespace>/rovenfall/quests/<id>.json`. The file
path becomes the stable quest ID. Titles and descriptions are translation keys;
IDs never depend on the player's language.

```json
{
  "translation_key": "quest.rovenfall.first_steps",
  "description_translation_key": "quest.rovenfall.first_steps.description",
  "version": 1,
  "prerequisites": [],
  "objectives": [
    {
      "id": "rovenfall:first_steps/claim_purchase",
      "kind": "claim_purchase",
      "required_count": 1
    }
  ],
  "rewards": {
    "currency": 100,
    "activity_xp": {"activity": "rovenfall:mining", "amount": 10}
  }
}
```

## Fields and limits

- `version` is a positive content version. Increase it when an objective or its
  meaning changes. Stored progress from another version is retained and reported
  as definition-changed; it is never silently converted or rewarded.
- `prerequisites` contains zero to 32 quest IDs. References must exist in the
  same validated snapshot, cannot repeat, and must form an acyclic graph.
- `objectives` contains one to 32 entries. Objective IDs are stable namespaced
  IDs and cannot repeat. `required_count` is between 1 and 1,000,000,000.
- `activity` requires an activity target. `claim_purchase` has no target.
  `shop_trade` and `boss_defeat` may omit a target to mean any eligible server
  outcome or provide one to narrow the future event adapter.
- `rewards` is optional. `currency` is between zero and 1,000,000,000 and
  `activity_xp`, when present, names an activity and awards between one and
  1,000,000,000 XP. Before any currency is paid, reward recovery verifies that
  the activity exists in the active RPG snapshot. Values are captured when the
  quest completes, so a later quest reload cannot change a pending reward.

Add both translation keys to `ko_kr`, `en_us`, and `ja_jp` for built-in content.
External data packs own their own language resources.

## Reload and player evidence

`QuestDefinitionReloadListener` prepares and validates the complete candidate
snapshot before publishing it. A duplicate ID, missing prerequisite, cycle,
invalid key, invalid target policy, unsafe count, oversized file, or oversized
catalog rejects the candidate and leaves the last good snapshot and revision in
place. The administration content-reload view reports quest problems separately.

`QuestPlayerSavedData` is an overworld-owned, versioned root separate from RPG,
economy, and land state. It stores bounded objective progress and immutable
completion receipts. Removed quest IDs remain stored and are exposed as
unresolved until an explicit content migration is shipped. A newer unsupported
schema loads read-only.

Gameplay adapters consume only durable server-owned outcomes: RPG-owned activity
outcomes, shop and land economy receipts, and completed boss reward operations.
An activity starts counting only while its matching quest objective is unlocked
and incomplete; activity before that activation point is intentionally not
retroactive. The RPG mutation stores the XP and recovery outcome atomically, so
the bounded 256-entry display history cannot erase unprocessed quest evidence.
Terminal deliveries remain replayable for 30 days after acknowledgement and are
then removed in bounded batches. Applied acknowledgements are delivered again
when the quest root lost its processed marker and are never reclaimed until that
marker is confirmed; ignored acknowledgements never become retroactive progress.
Per-player login recovery uses an owner-root
transaction index, and processed-ID maintenance rotates a bounded per-player
cursor so retained low IDs cannot starve later entries. The
recovery store is limited to
4,096 entries per player and 250,000 globally; a full store rejects the tracked
activity atomically instead of granting XP without quest evidence. Before a
tracked activity commits, the quest owner supplies its processed-ID view so old
applied outcomes can be reclaimed safely at either capacity boundary.
Processed quest IDs use a second two-phase retirement. Once an ID and its owner
evidence are more than 30 days old, absence is recorded; removal requires another
30 days of confirmed absence. If owner evidence is present when retirement is
confirmed, retirement is cancelled. Economy and boss owner evidence is replayed
only through this 60-day retirement horizon, so an old retained receipt cannot
advance progress again after its processed ID retires.

Processed source transaction IDs and captured reward phases survive restart.
Currency uses the deterministic completion transaction and activity XP uses a
deterministic child transaction through their owning services; an exact retry
cannot reward twice. A positive currency receipt is finalized in place as the
completion receipt. A zero-currency quest reserves a zero-amount receipt before
any RPG reward and finalizes that same receipt after all effects succeed. Thus
each quest consumes at most one of the platform's 50,000
non-expiring quest-receipt slots, and RPG quest-XP receipts have their own
50,000-entry limit. Platform quest receipts
remain below the shared 250,000-receipt ceiling, so they can occupy at most one
fifth of that ledger and leave at least 200,000 slots for expiring ordinary
economy traffic.
Every later reward phase rechecks the exact Economy, RPG, and platform-audit
evidence owned by those roots. The completion receipt retains the immutable
reward operation, so a quest-root save that wins a crash race can repair a
missing owner-root effect after restart without reopening the quest or paying
twice. The completion audit and its non-expiring marker commit in the same
platform root, so normal audit rotation does not reinsert old entries. Audit
recovery follows the 30-day audit-retention window; permanent currency and XP
receipts remain recoverable after it. Each call advances at most 8 reward
phases and reconciles at most 32 completed quests. Rotating per-player cursors
prevent failed or low-ID quests from starving later work. Clients never
report completion and quest code never edits another domain's state.

## Verification

Definition tests cover packaged data, graph validation, bounds, target policy,
and atomic reload failure. Player-state tests cover stable codec order,
duplicate evidence, stale compare-and-commit, migration, read-only future schema,
unresolved IDs, and definition-version changes. Finish content changes with the
focused quest tests, localization parity, the full test suite, and a clean build.
