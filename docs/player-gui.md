# Player GUI

Rovenfall turns the ordinary survival inventory into the primary player entry point. Opening the inventory shows the normal Minecraft inventory plus six compact tabs: Inventory, Overview, Claims, Skills, Shops, and Admin. The server opens Admin only for players with an administration role. Creative and spectator inventories are deliberately left unchanged.

## Interaction model

- Overview uses a three-row menu. Claims, Skills, and Shops use six-row menus.
- Mouse users activate an item with the primary (left) button. Shift-click, drag, number-key swaps, secondary clicks, and other inventory gestures cannot invoke an action.
- Keyboard users move the visible slot outline with the arrow keys, then activate the focused item with `Enter` or `Space`. In administration views, `Tab` moves focus into or out of the search/form field and `Enter` submits it.
- Screen narration announces the focused item, its menu position, and the activation keys. Empty positions are skipped.
- Back always returns one level, Refresh discards any open confirmation before rereading server state, and Previous/Next use the same labels in every paged menu.
- Destructive or paid actions show a confirmation page. A changed permission, price, definition, claim, balance, or RPG state makes that confirmation stale and prevents the mutation.
- Unavailable management controls are represented by a barrier with a written permission explanation; meaning never relies on item color alone.

Keyboard focus uses a bright outer ring with a dark inner edge, distinct from mouse hover.
Every visible action is a native focusable button. Both main Enter and keypad Enter submit a
focused administration search or typed form. Player cards hide UUIDs, namespaced identifiers,
and long evidence hashes by default; the focusable **Advanced details** button reveals and can
narrate that technical information when it is needed.

The server remains authoritative. The client sends only an open-tab request or a vanilla container click. Menu identity, session state, button type, permissions, prices, inventory contents, balances, definitions, and transaction results are validated on the server.

## Recovery and command fallbacks

The inventory tabs are the normal player path. `/rovenfall menu` reopens the Overview if a resource pack, another mod, or a screen transition prevents entry through the inventory. The following command roots remain emergency or automation fallbacks and execute the same server-owned services:

Rovenfall replaces only the exact vanilla survival `InventoryScreen`. Creative, spectator, and
subclassed inventory screens supplied by another mod remain untouched. Unknown container menus
also remain on their original screen unless the server sends an exact Rovenfall menu identity.
See [Custom UI release validation](ui-release-validation.md) for the supported GUI-scale matrix,
compatibility checks, and manual capture procedure.

- `/rovenfall shop buy|sell ...`
- `/rovenfall claim buy|info|trust|untrust|settings|transfer|sell ...`
- `/rovenfall career promote|switch ...`
- `/rovenfall skill learn|bind|unbind|reset ...`

Remote claim transfer acceptance or cancellation by dimension and chunk coordinates is command-only because the current-chunk GUI intentionally has no remote claim browser. `/rovenfall portal use <portal_id>` is also command-only until a player portal browser exists.

The Admin tab is the normal operator path. `/rovenfall admin gui` and the other `/rovenfall admin ...` commands remain permission-gated fallbacks for automation, recovery, and records outside bounded GUI result windows.

## RPG item costs

Career promotion and skill reset definitions may combine currency with exact item requirements. The confirmation page shows each item identifier, required quantity, and the player's current quantity. Changing the definition or the owned quantity while confirmation is open makes the action stale and requires a fresh confirmation.

- Career definitions use `promotion_items` and `full_reset_items`.
- Skill definitions use `branch_reset_items`.
- Each entry is `{ "item": "namespace:item", "count": N }`; unknown registry items, duplicate item identifiers, more than 16 entries, and counts outside `1..1,000,000` reject the definition reload.
- Commands and the inventory GUI call the same inventory-aware server services, so neither route can bypass item costs.

Item payment is journaled with an exact 36-slot inventory snapshot, required-item counts before and after consumption, the transaction identifier, and a bounded player-side completion receipt. Recovery handles all three independent save orders: platform-first consumes the still-present items, RPG-first reconstructs the platform payment, and an item escrow with neither platform nor RPG evidence is rolled back. Replaying a completed transaction does not consume currency or newly acquired copies of the required items.
