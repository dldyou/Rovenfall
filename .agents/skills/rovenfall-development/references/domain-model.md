# Domain model

Use stable names consistently in code, data, commands, audit records, and tests.

## Platform and identity

- **Player identity:** Minecraft UUID. Names are display data and never keys.
- **Definition ID:** Namespaced resource ID such as `rovenfall:warrior`. Display names are translation keys.
- **Hub:** Permanent shared dimension. Unowned land is immutable to ordinary players; eligible chunks can be purchased.
- **Wilderness:** Resettable resource dimension. Players may build and gather, but cannot claim land. Inventory, currency, and progression are shared with the Hub.
- **Protected region:** Administrator-owned region such as a portal ring, spawn, road, or boss arena. It overrides player-claim permissions.

## Economy

The initial economy uses one virtual integer currency stored in a player account. It is not an inventory item, has no fractional or negative balance, and never trusts a client-provided price or result. New accounts start at a configurable non-negative balance, initially zero; authorized balance grants use the audited economy operation rather than editing saved data.

Initial sources:

- selling to administrator shops;
- configured mob and boss rewards; and
- configured career activity rewards.

Initial sinks:

- buying from administrator shops;
- purchasing claims;
- career promotion; and
- skill-tree reset.

A **shop template** in data provides reusable default entries and policy. Templates are immutable at runtime and change through validated data reload. A runtime **shop instance** has a stable ID and owns its persistent catalog and stock so authorized operators can add, edit, or remove entries without rebuilding the mod. Multiple instances may use the same template.

A shop instance can bind to a dimension/position or a later NPC/block interaction adapter. Its access policy can constrain range, dimension, player role, career, or other registered conditions. An empty policy is public, but a position-bound shop still requires server-validated proximity. The economy service remains independent of the eventual screen, block, NPC, or command entry point.

Each **shop offer** defines an exact input or output `ItemStack` including count and data components, plus one price per complete offer unit. A request quantity counts offer units; partial offers and quantity discounts are outside the initial scope. An offer can define buy price, sell price, current and maximum stock, unlimited stock, and scheduled restock. Runtime catalog mutation is validated, atomic, and audited. Direct transfers and player-owned shops are later features.

## Activities, careers, and skills

Activity proficiencies are permanent independent tracks:

- **Combat:** meaningful damage, defense, and valid combat skill use, capped per target.
- **Cooking:** collecting a completed configured food result.
- **Mining:** breaking eligible naturally generated resources.
- **Exploration:** first discovery of configured biomes, structures, or regions.
- **Hunting:** credited participation in ordinary, rare, and boss kills.
- **Building:** the first valid placement of configured blocks on the player's claim.
- **Farming:** harvesting properly matured crops and completing eligible breeding actions.

A **career definition** has a stable ID, arbitrary positive tier, zero or more parent career IDs, prerequisites, level curve, rewards, and skill tree. Career definitions form an acyclic graph and may branch at any tier.

A player retains progress for every promoted career but has exactly one active career. Any previously promoted career, including a sibling branch or ancestor, may be reactivated without resetting progress; an unpromoted career cannot be selected. Career and passive skill effects work only for the active career and its ancestor lineage unless a future effect type explicitly declares a broader scope.

Promotion conditions can combine career level, activity levels, prerequisite skills, currency, and items. Validation and cost consumption are one transaction.

Skill points belong to a career and come from career levels or promotion rewards. A **skill node** has a stable ID, prerequisite nodes, maximum rank, point cost, and typed effects. Tree definitions and balance live in data; reusable effects use registered effect types, while genuinely complex behavior uses a Java handler registered by ID. Arbitrary scripts are outside scope.

One skill point is granted for each newly reached career rank. Learning spends points from the skill's owning promoted career and validates every prerequisite rank across the career lineage. A branch reset removes one learned skill plus every transitive learned dependent; a full reset starts with all learned skills owned by one career and applies the same dependent closure. Both refund the exact points originally implied by the current validated definitions. Paid resets use a durable cross-root operation and cannot be refunded through generic economy reversal.

Players can equip four active skills. Client keybinds express intent; the server validates active career, learned rank, slot, cooldown, target, range, and context. The initial cost model is cooldown-only, with room for a future registered resource-cost type.

## Claims and protection

An eligible unowned Hub chunk may be purchased without adjacency to existing claims, but the player must stand in that chunk. Base price, escalation, and ownership cap are server settings. Protected or ineligible chunks cannot be purchased.

Roles:

- `OWNER`: ownership, sale, transfer, settings, and all use;
- `MANAGER`: trust and public-setting management;
- `BUILDER`: placement and destruction;
- `USER`: containers, doors, buttons, and allowed interactions; and
- `VISITOR`: movement only.

Movement through claims is allowed by default. The owner may enable entry restriction. For unauthorized actors, protection covers placement, destruction, containers, entities, redstone interactions, fluids, pistons, fire, and explosions. A blast that would alter protected state is cancelled or clipped without leaking partial damage into the claim.

Claims may be sold for a configurable partial refund or transferred with confirmation from both parties. Inactivity never causes automatic repossession; administrators review and reclaim through an audited operation.

Hub PvP is disabled by default. Wilderness PvP is enabled by default and remains server-configurable.

## Worlds and portals

The first topology is one permanent Hub and one manually reset Wilderness. Wilderness reset:

1. announces the irreversible loss of Wilderness blocks, containers, and entities;
2. evacuates players to the Hub;
3. creates a snapshot;
4. replaces the dimension safely; and
5. records one audited operation.

Only administrators create portals initially. A portal has a stable ID, origin, destination dimension, coordinates or search rule, protection radius, cooldown, and safe-arrival policy. Portal definitions must support additional destinations without special-case code. Players cannot arrive inside blocks, hazards, or unauthorized enclosed claims.

## Mobs, mutations, and bosses

The first content milestone contains two ordinary custom mobs and one multi-pattern boss.

A **mutation definition** composes attribute changes, AI behavior modifiers, visible markers, spawn conditions, and reward changes onto an eligible existing mob. Mutations spawn at low configured rates in the Wilderness and never in the Hub.

Bosses use protected arenas or managed spawn points. Rewards are personal to players who pass a server-calculated contribution threshold; last hit alone grants no ownership. Reward cooldowns prevent repeated farming.

Every administrator role may inspect loaded mutated mobs and durable boss encounter, participant, phase, reward, and cooldown evidence. Only `OWNER` may force-reset an encounter or run stuck-encounter recovery. A reset must retain a `REWARD_PENDING` encounter until its full reward intent is durably represented in the reward ledger.

## Administration and observability

Administrator roles are independent of claim roles:

- `VIEWER`: dashboards and audit queries;
- `MODERATOR`: player and claim operations;
- `ECONOMY_MANAGER`: currency and shop operations;
- `CONTENT_MANAGER`: career, skill, portal, and content reload operations; and
- `OWNER`: backups, migrations, Wilderness reset, and recovery.

The first admin interface combines permission-gated mutation commands with read-only searchable views for player state, balances, transactions, claims, shops, denied actions, and alerts. Audit queries combine bounded time, actor, action, target-prefix, and transaction filters with AND semantics and deterministic newest-first pagination. Only `OWNER` may export the exact result as capped JSON Lines under the server-owned operations directory; callers never choose a filesystem path. External dashboards and automatic punishment are outside the initial scope.

Boss operations use a caller-supplied transaction ID. They append an immutable request audit before changing state and a deterministic completion audit only after cleanup or recovery is verifiably complete. Exact retries resume an incomplete request or return duplicate after completion.

## Localization

Every visible name and message uses a translation key. Ship complete `ko_kr`, `en_us`, and `ja_jp` catalogs. English is the fallback content language; stable IDs never depend on translated text.
