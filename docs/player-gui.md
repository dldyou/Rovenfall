# Player GUI

Rovenfall turns the ordinary survival inventory into the primary player entry point. Opening the inventory shows the normal Minecraft inventory plus eight compact tabs: Inventory, Overview, Land, Travel, Skills, Shops, Journey, and Admin. The server opens Admin only for players with an administration role. Creative and spectator inventories are deliberately left unchanged.

## Interaction model

- Overview uses a three-row menu. Land, Skills, Shops, and Journey use six-row menus.
- Land opens the Land Atlas: Current Land, My Land, Nearby Land, and Available Land. The atlas is a bounded, server-filtered list; restricted land and owner names are never sent to players who cannot view them. Search is limited to visible land and uses an owner name rather than a raw identifier.
- A land card can set a locator-bar waypoint in the current world. Clearing the waypoint is available from the atlas. The atlas never loads remote areas, and purchase remains available only while standing on the selected available land.
- Travel opens the Portal Explorer. It lists server-approved portal cards with an origin/destination world search, bounded pages, origin world, destination world, distance, and a current-world navigation marker. Portal IDs and coordinates stay in Technical information only. Navigation never loads a remote world.
- Portal use is an on-site action: the server rechecks the selected portal, protection, current dimension, 8-block entrance distance, cooldown, combat lock, and safe arrival at activation time. A changed portal refreshes the card instead of travelling the player.
- Journey is the quest board. It shows at most 28 journeys on a page, a plain-language status and reward preview, then one server-selected next step. The top row directly filters All, Needs action, In progress, Blocked, or Completed journeys before paging. Its Requests sub-screen shows at most two daily requests and one weekly request. Its Exploration Journal shows public destinations and places the player has discovered without revealing secret coordinates. The Overview card opens the same board, so players never need a command, UUID, or definition identifier to find their next activity.
- Skills opens the RPG screen. Its **Challenges** card lists one-time activity milestones with claimable rewards first, followed by in-progress and completed goals. Select a claimable card once to collect its reward; the server rechecks every requirement and its existing reward receipt before changing the balance, so stale or repeated clicks cannot pay twice.
- A player can pin one available or in-progress story journey, daily request, or weekly request to the active-journey tracker. Select the shown card once to pin or replace the tracker; selecting the pinned card again, or using **Stop showing**, clears it. This is a display preference for the next unfinished objective, not a client-owned quest action.
- The first journey introduces the opening loop: gain Mining activity progress, trade at a shop, then buy a piece of land. It is guidance rather than a timer; all progress and any reward remain server-owned.
- Mouse users activate an item with the primary (left) button. Shift-click, drag, number-key swaps, secondary clicks, and other inventory gestures cannot invoke an action.
- Keyboard users move the visible slot outline with the arrow keys, then activate the focused item with `Enter` or `Space`. `Esc` activates the visible Back control one level at a time (and keeps Minecraft's normal close behavior at a root screen), `R` refreshes, `Ctrl+F` or `/` focuses an available search field, and `Page Up`/`Page Down` changes pages. In administration views, `Tab` moves focus into or out of the search/form field and `Enter` submits it; the first `Esc` leaves a focused text field without discarding the whole menu.
- Screen narration announces the focused item, its menu position, and the activation keys. Empty positions are skipped.
- Back always returns one level, Refresh discards any open confirmation before rereading server state, and Previous/Next use the same labels in every paged menu.
- A changed quest definition or player state makes a Journey page stale. The board refreshes from the server instead of accepting a stale interaction. If quest data is temporarily read-only, journeys and current requests keep their existing progress visible and explain that no update can be made.
- Destructive or paid actions show a confirmation page. A changed permission, price, definition, claim, balance, or RPG state makes that confirmation stale and prevents the mutation.
- Unavailable management controls are represented by a barrier with a written permission explanation; meaning never relies on item color alone.

Keyboard focus uses a bright outer ring with a dark inner edge, distinct from mouse hover.
Every visible action is a native focusable button. Both main Enter and keypad Enter submit a
focused administration search or typed form. Player cards hide UUIDs, namespaced identifiers,
quest and objective references, and long evidence hashes by default; the focusable **Advanced details** button reveals and can
narrate that technical information when it is needed.

The server remains authoritative. The client sends only an open-tab request or a vanilla container click. Menu identity, session state, button type, permissions, prices, inventory contents, balances, definitions, and transaction results are validated on the server.

## Daily and weekly requests

Open **Journey**, then select **Requests** in slot 46. The server initializes the current UTC roster before projecting the screen; the menu itself is read-only and contains no completion or reward button. Cards use the same server-observed activity, shop, and boss outcomes as journeys.

- Daily requests refresh at 00:00 UTC and weekly requests refresh on Monday at 00:00 UTC.
- A player sees at most three cards: two daily and one weekly.
- Each card shows its plain-language name, one objective, current progress, reward preview, and refresh schedule.
- Normal details never show a template ID or UTC window number. **Technical information** may reveal those values for diagnostics.
- Refresh rereads the current roster without rerolling it. Back returns to the journey board.
- An empty or read-only roster remains a usable, narrated screen and never asks the client to create progress.

## Adventure HUD and active journey tracker

The upper-right Adventure HUD combines a north-up terrain minimap, current dimension and biome,
XYZ coordinates, eight-way heading, and the active journey panel. The minimap samples only loaded
client chunks every ten ticks or after meaningful movement; it never requests or reveals unloaded
terrain. The tracker is the quick, command-free way to keep one chosen story journey, daily request,
or weekly request visible while playing.

- Open **Journey**. In a story detail, select **Show on screen**. In **Requests**, select a shown
  daily or weekly card. Selecting a different eligible card replaces the current tracker; selecting
  the same card clears it. **Stop showing** is also available from the Journey board when a tracker
  is active.
- The quest card shows only a localized title, story/daily/weekly label, available or in-progress state,
  one incomplete objective with progress, and the daily or weekly refresh wording when relevant.
  It never shows a UUID, quest/template/objective ID, request window, coordinate, hidden target,
  reward, or a technical definition revision.
- The server owns the selection and its content version. It accepts only the authenticated vanilla
  menu click, rechecks that the shown selection is still eligible, and clears an expired, completed,
  removed, changed, or future-schema selection rather than displaying stale guidance. The client
  cannot submit progress, rewards, identifiers, locations, or a revision.
- A bounded server-to-client snapshot is at most 384 bytes. It is refreshed on login, a tracker
  selection or clear, observed quest progress, definition reload, request rotation, and a bounded
  20-tick reconciliation pass. Duplicate snapshots are not resent; the periodic pass examines at
  most 16 players at once.
- The panels follow the selected GUI scale, stay within the screen edge, and disappear whenever
  another screen is open or the player uses the F1 HUD toggle. Minecraft narration announces the
  same localized tracker title, state, and objective information; it does not repeat every tick.
- Press `H` during gameplay to cycle **Full** (minimap, location, coordinates, and journey),
  **Quest only**, and **Hidden**. The selected mode is a client display preference and never changes
  quest progress. `H` is a normal rebindable Minecraft control under the Rovenfall HUD category;
  each change is confirmed in the action bar and narrator.
- `ko_kr`, `en_us`, and `ja_jp` all ship the same tracker controls, status, objective, and refresh
  wording. Korean player-facing text uses natural terms such as `여정`, `일일 의뢰`, and `주간 의뢰`.

The survival HUD replaces vanilla hearts, hunger icons, and the level number with compact RPG bars
for exact health, hunger, saturation, absorption, and Minecraft experience level. It changes only
client rendering: server health, food, XP, armor, air, mount health, and gameplay rules remain
unchanged. Creative and spectator HUDs keep their ordinary behavior.

## Exploration journal

Open **Journey**, then select **Exploration Journal** in slot 47. The journal can show all places,
Hub places, or Wilderness places. Public destinations are visible before discovery; a private
undiscovered destination is an anonymous placeholder with no title, description, identifier,
world, position, radius, version, or reward data.

- Discovery occurs only when the server observes the player inside the configured area.
- A versioned receipt survives restart and reload. Moving a definition requires entering its new
  area, but its one-time activity XP reward is never granted twice.
- A visible card can set a native waypoint only when the destination is in the player's current
  world and is either public or discovered under the current definition version.
- Clear removes only the Exploration Journal marker. Land and Travel markers use separate stable
  marker identifiers.
- Waypoints never teleport, reveal another-world coordinates, or load remote chunks.

See [Exploration definitions](exploration-definitions.md) for the data-pack schema and privacy
boundary.

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

Remote land management is available from the atlas only when the server grants the viewer the existing owner, manager, or pending-recipient permission. Purchase still requires standing on the land, and the server rechecks the selected land before every action. The Travel tab is the normal portal path; `/rovenfall portal use <portal_id>` remains an operator, recovery, and automation fallback using the same server checks.

The Admin tab is the normal operator path. `/rovenfall admin gui` and the other `/rovenfall admin ...` commands remain permission-gated fallbacks for automation, recovery, and records outside bounded GUI result windows.

## RPG item costs

Career promotion and skill reset definitions may combine currency with exact item requirements. The confirmation page shows each item identifier, required quantity, and the player's current quantity. Changing the definition or the owned quantity while confirmation is open makes the action stale and requires a fresh confirmation.

- Career definitions use `promotion_items` and `full_reset_items`.
- Skill definitions use `branch_reset_items`.
- Each entry is `{ "item": "namespace:item", "count": N }`; unknown registry items, duplicate item identifiers, more than 16 entries, and counts outside `1..1,000,000` reject the definition reload.
- Commands and the inventory GUI call the same inventory-aware server services, so neither route can bypass item costs.

Item payment is journaled with an exact 36-slot inventory snapshot, required-item counts before and after consumption, the transaction identifier, and a bounded player-side completion receipt. Recovery handles all three independent save orders: platform-first consumes the still-present items, RPG-first reconstructs the platform payment, and an item escrow with neither platform nor RPG evidence is rolled back. Replaying a completed transaction does not consume currency or newly acquired copies of the required items.
