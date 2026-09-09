# Expedition food

For hunting-material weapons, tools, and enchanted books, see
[Expedition equipment](expedition-equipment.md).

Both recipes are shapeless and fit the inventory's 2×2 crafting grid. Pick up
the unlock ingredient to find the recipe in the recipe book. These are ordinary
foods, not tonics: they provide nutrition without status effects or empty bowls.

| Food | Ingredients → output | Unlock ingredient | Hunger / saturation | Stack | Cooking XP per item |
| --- | --- | --- | --- | --- | --- |
| Trail Ration (탐험 건량) | Cooked rabbit + wheat + dried kelp → 2 | Cooked rabbit | 8 / 12.8 | 64 | 3 |
| Orchard Pie (과수원 파이) | Apple + honey bottle + egg + wheat → 2 | Apple | 8 / 4.8 | 16 | 4 |

Orchard Pie crafting returns the empty glass bottle. Trail Rations use vanilla
cooked-beef nutrition; Orchard Pies use vanilla pumpkin-pie nutrition. The item
models currently reuse Minecraft's cooked-rabbit and pumpkin-pie sprites.

## Daily cooking requests

Open Journey (`J`, rebindable), then **Wilderness Daily Tasks** in toolbar slot 52.
Use the **All / Cooking** filter to narrow the list. Craft the named food in the
Wilderness, refresh the board, and select a ready card to collect its reward.
Ready cards appear first; collected cards appear last. These commands remain an
alternative for inspection and collection:

- Trail Supplies: 48 accepted Cooking XP from Trail Rations → 100 currency.
- Orchard Supplies: 48 accepted Cooking XP from Orchard Pies → 120 currency.

```text
/rovenfall contract info rovenfall:trail_ration_supplies
/rovenfall contract claim rovenfall:trail_ration_supplies
/rovenfall contract info rovenfall:orchard_pie_supplies
/rovenfall contract claim rovenfall:orchard_pie_supplies
```

These contracts reset at 00:00 UTC and allow one reward claim per player per day.
They are distinct from Journey's rotating Requests roster. Both are reachable
from Journey, but only Wilderness Daily Tasks use explicit reward collection.
`Esc` returns from daily tasks to Journey; `R` refreshes the current page. If a
definition, progress, daily window, or reward status changed since the card was
shown, the first click refreshes it without awarding currency. Select the newly
shown ready card to collect the current reward.

At default rates this is 16 rations (8 crafts) or 12 pies (6 crafts). Food already
in your inventory, eating food, or moving stacks does not advance crafting
requests. Existing server rate limits can reduce accepted XP; the displayed XP
progress is authoritative. Each food has a 48-XP target cap per 60 seconds and
shares the existing player limits. No new save schema or client reward packet
is introduced.

Operators can tune recipes under `data/rovenfall/recipe`, XP under
`data/rovenfall/rovenfall/activity_rewards`, and requests under
`data/rovenfall/rovenfall/daily_contracts` through a data pack. Inspect the player's
Cooking track in the external admin console to confirm accepted progress.
