# Administration control center

The `Admin` tab in the RPG inventory shell opens the stable operator entry point without
a command. `/rovenfall admin gui` remains an equivalent fallback, and existing
`/rovenfall admin ...` commands remain available for automation and emergency use. The
server rejects the inventory-tab request when the player has no administration role.

## Role-visible domains

| Role | Read-only domains |
| --- | --- |
| Viewer | Players, claims, shops, portals, RPG, encounters, audit, alerts, metrics, receipts |
| Moderator | Players, claims, RPG, audit, metrics |
| Economy manager | Players, shops, audit, alerts, metrics, receipts |
| Content manager | Players, portals, RPG, encounters, audit, metrics |
| Owner | All domains |

Changing or removing an operator role while the menu is open invalidates the current
view immediately. Every click and search submission rechecks the server-owned role,
container identity, and session state.

## Search and bounds

Each domain has a text search field, an `All`/`Attention required` filter, refresh,
and previous/next page controls. Search is case-insensitive over the identifiers and
summary fields shown by that domain. Ordinary list searches are limited to 64 characters;
Audit `key=value` search accepts at most 1,024 characters, while the shared mutation/export
form transport accepts at most 2,048 before applying its stricter field and 160-character
reason limits. Each page contains at most 36 rows, and a refresh scans at most 1,000 rows
from bounded domain query services. The menu marks a result as truncated when the source exceeded
that scan budget. Player UUID and economy transaction UUID searches use direct indexed
lookup even when the target is outside the recent 1,000-row window. Other results outside
that bounded window require the domain command fallback for a precise target lookup.

## Player, economy, and shop management

The Players, Shops, and Economy receipts domains open typed management views. Read-only
roles may inspect the same bounded data, while only Economy manager and Owner may see or
submit mutation controls. Player lookup searches the last persisted display name but a
selected row always carries the server-owned UUID.

Every mutation uses a single-line `fields | reason` form and opens a server-generated
preview. The server allocates the transaction ID, retains the selected UUID or identifier,
and snapshots the current balance, receipt, online target inventory, or complete shop instance. Confirming rechecks
the role and exact snapshot before calling `EconomyService`, `EconomyReversalService`, or
`ShopInstanceService`. Stale previews are rejected and audited; replaying an already
committed transaction is handled by the existing idempotency ledger.

Balance grant/debit, receipt reversal or explicit compensation, shop create/delete,
binding, access distance, offer price/item/stock changes, and restock policies are
available. Only receipt kinds supported by generic economy reversal expose a confirmation;
domain-owned receipts show a localized unavailable explanation. Receipt reversal follows
the command boundary and therefore requires the target player to be online. Existing commands remain the precise fallback for records
outside the 1,000-row GUI window.

## Claim, protected-region, portal, and Wilderness management

Claims and Portals open typed inventory management screens. Viewer roles can inspect the
same bounded rows. Moderators and Owners can change claim trust and settings or reclaim an
abandoned claim without issuing a refund. Only Owners can create, edit, or delete protected
regions. Content managers and Owners can create, edit, or disable portals; disabling uses
the existing portal delete service and atomically removes both derived protection regions.

Claim detail lists every trusted role with pagination and shows owner, flags, purchase
evidence, and pending transfer state. Protected-region and portal forms validate dimensions,
chunk or block bounds, world borders, endpoint conflicts, protection dependencies,
safe-arrival policy, cooldown, and combat policy. A selected row binds the exact claim,
region, or portal snapshot to the preview. Confirming rechecks the current role and full
snapshot before the owning domain service is called.

The Wilderness screen shows Hub/Wilderness availability, safe Hub arrival readiness,
players awaiting evacuation, encounter/filesystem locks, the active warning, any staged
operation, and retained reset/restore evidence. Owners can issue a ten-minute reset warning,
then preview and confirm a reset. Completed evidence exposes both the target snapshot and
the safety snapshot for restore. Reset, restore, claim reclaim, region deletion, and portal
disable are labeled irreversible and always require a form submission, a current-state
preview, and a second explicit confirmation. Wilderness reset/restore still use the existing
snapshot, evacuation, precommit, rollback, and restart lifecycle services; the GUI never
edits saved collections or filesystem state directly.

## RPG content, custom mobs, and boss operations

The RPG domain lists persisted players, progression, award evidence, promotion recovery
candidates, and all loaded activity, career, skill, mob, mutation, arena, contribution,
reward, and boss definitions. Moderators and Owners may adjust activity XP. Content
managers and Owners may recover a missing promotion, reset a career or skill branch, and
request a normal datapack reload. Every mutation binds the exact player state and RPG
definition revision shown by its preview; skill resets also bind the server-computed reset
plan.

The reload screen retains a bounded diagnostic list with source, file, definition ID, and
sanitized cause. A failed validation keeps the previously active RPG and mob snapshots.
Only one audited reload can run at a time, and completion is shown separately from the
initial request result.

The Encounters domain shows active mutations, boss stages, protected-arena state,
participant contribution ratios, reward eligibility inputs, and reward-operation phases.
Only Owners can reset one encounter or run global recovery. The confirmation snapshot
includes the encounter, its arena, and reward operations (or the complete global recovery
set), then rechecks that evidence at click time before calling the existing resumable,
restart-safe boss administration service.

## Audit, alerts, metrics, and platform recovery

The Audit, Alerts, and Metrics domains use the same inventory shell. Audit supports a
bounded 30-day `key=value` query, 36-row pages, an attention-only filter, and exact
transaction evidence. Audit detail can open a linked economy receipt directly when that
receipt is visible to the current role. Alerts can be filtered by amount or rate and
Metrics offers 15-minute, one-hour, six-hour, and 24-hour windows; each listed metric
transaction opens the matching audit evidence. GUI scans stop after 1,000 rows and mark
the result as truncated instead of loading unbounded history.

Owners can export the exact selected audit window from the Audit screen. Export requires
explicit `since` and `until` millisecond fields plus a reason, then shows the bounded row
and byte limits before confirmation. The server retains the exact selected entries and
rechecks the Owner role and selection at click time before calling `AuditExportService`.
The GUI never exposes an audit delete operation. Older or exact records remain available
through `/rovenfall admin audit search`.

The Snapshot screen lists platform snapshot IDs derived from immutable audit evidence.
Creating a snapshot and restoring a selected snapshot both require `| reason`, a
server-generated transaction ID, and a second confirmation. The preview contains the
bounded encoded byte count and SHA-256 evidence. Confirmation compares the current
platform fingerprint and, for restore, the selected snapshot fingerprint before calling
`AdministrationService`. Restore always allocates a separate safety snapshot ID first.
Missing, changed, oversized, malformed, stale, replayed, rate-limited, or unauthorized
requests fail closed and preserve denial or failure evidence through the owning service.
