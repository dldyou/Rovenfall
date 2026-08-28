# Player GUI

Rovenfall turns the ordinary survival inventory into the primary player entry point. Opening the inventory shows the normal Minecraft inventory plus five compact tabs: Inventory, Overview, Claims, Skills, and Shops. Creative and spectator inventories are deliberately left unchanged.

## Interaction model

- Overview uses a three-row menu. Claims, Skills, and Shops use six-row menus.
- Mouse users activate an item with the primary (left) button. Shift-click, drag, number-key swaps, secondary clicks, and other inventory gestures cannot invoke an action.
- Keyboard users move the visible focus outline with the arrow keys or `Tab`/`Shift+Tab`, then activate the focused item with `Enter` or `Space`.
- Screen narration announces the focused item, its menu position, and the activation keys. Empty positions are skipped.
- Back always returns one level, Refresh discards any open confirmation before rereading server state, and Previous/Next use the same labels in every paged menu.
- Destructive or paid actions show a confirmation page. A changed permission, price, definition, claim, balance, or RPG state makes that confirmation stale and prevents the mutation.
- Unavailable management controls are represented by a barrier with a written permission explanation; meaning never relies on item color alone.

The server remains authoritative. The client sends only an open-tab request or a vanilla container click. Menu identity, session state, button type, permissions, prices, inventory contents, balances, definitions, and transaction results are validated on the server.

## Recovery and command fallbacks

The inventory tabs are the normal player path. `/rovenfall menu` reopens the Overview if a resource pack, another mod, or a screen transition prevents entry through the inventory. The following command roots remain emergency or automation fallbacks and execute the same server-owned services:

- `/rovenfall shop buy|sell ...`
- `/rovenfall claim buy|info|trust|untrust|settings|transfer|sell ...`
- `/rovenfall career promote|switch ...`
- `/rovenfall skill learn|bind|unbind|reset ...`

Remote claim transfer acceptance or cancellation by dimension and chunk coordinates is command-only because the current-chunk GUI intentionally has no remote claim browser. `/rovenfall portal use <portal_id>` is also command-only until a player portal browser exists.

Administrator workflows are not exposed in the player inventory. They remain under `/rovenfall admin ...` and are permission-gated and audited.
