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
  ]
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

This foundation does not listen to gameplay events or grant rewards. A gameplay
adapter must observe a completed server-owned outcome, compare the active
definition version, update progress once, and call the owning Economy or RPG
service for any reward. Clients never report completion and no quest code writes
another domain's saved data directly.

## Verification

Definition tests cover packaged data, graph validation, bounds, target policy,
and atomic reload failure. Player-state tests cover stable codec order,
duplicate evidence, stale compare-and-commit, migration, read-only future schema,
unresolved IDs, and definition-version changes. Finish content changes with the
focused quest tests, localization parity, the full test suite, and a clean build.
