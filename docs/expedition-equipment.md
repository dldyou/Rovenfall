# Expedition equipment and enchantments

Hunting materials now lead to two crafted enchanted tools and two reusable
rank-I enchanted-book recipes. Pick up a Frostbound Shard or Deepstone Core to
unlock the matching equipment and book in the recipe book.

| Equipment | Material tier | Crafted enchantment | Durability |
| --- | --- | --- | --- |
| Rimeblade / 서리날 | Iron sword | Rime Edge I | 250 |
| Deepstone Pickaxe / 심층석 곡괭이 | Diamond pickaxe | Delver's Reach I | 1,561 |

## Crafting

Use a crafting table for equipment. In the diagrams below, `.` is an empty slot.

```text
Rimeblade             Deepstone Pickaxe
. F .                 D D D
F I F                 . C .
. S .                 . S .

F = Frostbound Shard  D = Diamond
I = Iron tool material (normally an iron ingot)
S = Stick             C = Deepstone Core
```

The two book recipes are shapeless and fit the inventory's 2×2 grid:

- Book + Runebound Fragment + Frostbound Shard → Rime Edge I book.
- Book + Runebound Fragment + Deepstone Core → Delver's Reach I book.

Frostbound Reavers drop Frostbound Shards, Deepstone Husks drop Deepstone Cores,
and Runebound Archers drop Runebound Fragments. Hunt them in the Wilderness.
Each equipment or book recipe makes one item.

## Enchantment behavior

- **Rime Edge / 서리 베기 (I–II):** a direct melee hit inflicts Slowness I for
  2 seconds at rank I or 3 seconds at rank II. Arrow hits do not activate it.
  Applies to swords, including Rovenfall's hunting swords.
- **Delver's Reach / 심층 도달 (I–II):** adds 0.5 or 1 block to block-interaction
  reach while the enchanted pickaxe is in the main hand. This affects mining and
  block interactions, not melee attack reach. It gives no benefit in the offhand
  and does not bypass land protection or permission checks.

Use a native anvil to apply books to compatible equipment. Combining matching
rank-I enchantments produces rank II; rank II is the maximum. Ordinary XP costs,
repair costs, and anvil rules apply. Use a grindstone to remove these enchantments.
They are not added to the random enchanting-table or trading pools: crafting
provides the dependable acquisition route.

The enchantments belong to the crafted item stack, not a permanent hidden item
ability. A plain `/give rovenfall:rimeblade` or `/give rovenfall:deepstone_pickaxe`
has the native material statistics but no added enchantment; use the recipes or
anvil books for the enchanted versions. Both tools use existing Minecraft item
textures with the native enchantment glint.

## Operator data and verification

Effect values, maximum ranks, compatible item tags, and anvil costs live under
`data/rovenfall/enchantment`. Equipment and books live under
`data/rovenfall/recipe`; recipe unlocks are under
`data/rovenfall/advancement/recipes/equipment`. These use native Minecraft codecs,
crafting, anvil, equipment, and enchantment hooks, without a new network packet,
custom attack loop, or persistence root.

The `expedition_equipment_crafting` GameTest covers matching/rejected crafting
inputs, enchanted output, native durability/mining tier, item serialization,
book crafting, anvil upgrade, incompatible input rejection, grindstone removal,
and loaded recipe unlocks. `expedition_enchantment_effects` checks direct versus
indirect attacks and main-hand versus offhand reach.

The [Rune Sentinel](rune-sentinel.md) provides another Wilderness source of
Runebound Fragments for these books, with a telegraphed strike and a daily hunt.
