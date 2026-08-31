# Custom UI release validation

This is the release evidence contract for the code-drawn inventory, player menus, and operator
console. Geometry and compatibility have automated checks; visual rendering, narration output,
and interaction with third-party inventory mods still require a real client.

## Automated logical-resolution matrix

Minecraft lays screens out in logical pixels after GUI scaling. The layout tests cover every
custom screen at these logical sizes:

| Logical size | Expected layout |
| --- | --- |
| 320 × 240 | Minimum supported view; all seven inventory tabs and one-column cards |
| 426 × 240 | Common 1280 × 720 high-GUI-scale view |
| 640 × 360 | Medium view; two-column administration and player cards |
| 854 × 480 | Common 16:9 GUI view |
| 1920 × 1080 | Large unscaled/windowed view with bounded panel width |

The tests require panels, cards, detail regions, paging, Technical information, toolbars, all seven inventory
tabs, and character summary to remain on screen without collisions. They also enforce at least
3:1 adjacent contrast for the two-tone keyboard focus ring. The minimum supported logical size is
320 × 240; smaller dimensions are outside Minecraft's normal GUI-scale floor and are not an RC
target.

## Compatibility contract

- Replace only the exact vanilla survival `InventoryScreen` for an alive, non-spectator,
  non-creative player using their own inventory menu.
- Leave subclasses and replacement inventory screens from other mods untouched.
- Leave creative and spectator inventory behavior untouched.
- Replace a generic player container only after consuming the exact server-issued Rovenfall menu
  identity. Unknown, stale, malformed, or already-consumed identities fall back to the original
  container screen.
- Open administration menus through their registered custom menu types; the identity path remains
  a compatibility fallback.
- Resource packs cannot remove the code-drawn focus ring, card frames, or text colors. If another
  mod prevents the inventory entry point, `/rovenfall menu` and `/rovenfall admin gui` remain the
  permission-gated fallbacks.

## Manual client matrix and capture manifest

Status for this repository change: **standard Korean client capture passed**. The images below
were captured with Minecraft's F2 screenshot action from a real local multiplayer client; they
are not mockups. Narration, additional resolutions, other locales, resource packs, and declared
third-party inventory mods remain explicit release-candidate checks rather than inferred passes.

Capture environment: Minecraft 26.2, NeoForge 26.2.0.66, Rovenfall issue #101 worktree,
Windows local client, 854 × 480 framebuffer, `ko_kr`, vanilla resources, no inventory mod,
survival player, Owner operator role. The committed image files bind the evidence to the Git
revision that contains this manifest.

- [Korean custom inventory](release-evidence/ui/issue-101/ko_kr-inventory-854x480.png): the
  inventory opens directly with the code-drawn RPG frame, character summary, slots, and six tabs.
- [Korean administration console](release-evidence/ui/issue-101/ko_kr-administration-854x480.png):
  the Management tab opens the role-aware custom console without requiring a command or raw ID.

For a repeatable local multiplayer capture, start `runServer`, then launch the client with
`./gradlew runClient -PquickPlayMultiplayer=localhost`. The property is optional and leaves the
normal client run unchanged.

## Issue #110 journey-board release checks

Issue #101 screenshots above are historical evidence and predate the Journey tab. Before an
Issue #110 release, repeat the client matrix with the seventh tab visible and capture the Journey
board in all three locales. Verify both the Journey tab and the Overview next-step card open the
same board. The board must retain at most 28 logical quest entries per page; Previous and Next
must remain reachable with mouse, `Tab`, arrow keys, `Page Up`, and `Page Down`.

For the shipped first journey, verify the next step advances from Mining activity to a shop trade
to a land purchase only after server-observed evidence. Refresh after a definition reload or a
progress change and verify stale information is rejected rather than acted on. In normal details,
verify quest and objective references are absent; enable **Technical information** and verify the
focused reference is narrated. With Minecraft narration enabled, verify the Journey title, current
page/card position, status, objective progress, next step, disabled/read-only state, and keyboard
activation guidance are spoken without relying on color.

## Issue #111 land-atlas release checks

Before an Issue #111 release, repeat the standard client matrix for the Land Atlas in `ko_kr`,
`en_us`, and `ja_jp`. Confirm that all labels use ordinary player language and that normal cards
do not show raw identifiers or technical positions.

| Case | Required state | Expected result |
| --- | --- | --- |
| Current and owned | Player stands on owned land and has at least two owned lands | Current Land and My Land open usable cards; owner management still follows the server permission check. |
| Nearby privacy | Include owner, explicitly permitted, public, and restricted nearby land | Only land the viewer may see is listed; hidden land and owner names never appear in search results or narration. |
| Available purchase | Include nearby protected and available land | Only eligible available land is listed; setting a waypoint works, but purchase is offered only after the player travels there. |
| Search and paging | Seed more than one page of visible land | Search, Previous, Next, keyboard focus, and narration stay reachable; no result exceeds the bounded page size. |
| Navigation | Set and clear a waypoint, then select another world | The locator-bar marker appears and clears in the current world; another-world navigation explains the limit without loading the remote area. |
| Stale selection | Change land ownership, permission, or availability before activating a shown card | The server rejects the stale selection, refreshes the atlas, and performs no unintended mutation. |

## Issue #101 release-candidate result

The 2026-08-30 release-candidate run used the Gradle-managed Eclipse Temurin 25.0.4 daemon and
executed `clean build recoveryRehearsal` with version `1.0.0-rc.101`.

- 412 JUnit tests passed.
- All 32 required GameTests passed.
- Performance-budget and recovery-rehearsal tasks passed.
- `inspectReleaseJar` accepted the 1,769,868-byte distributable.
- SHA-256: `E48DDC5608D88222F0947677A89F5C6B334EBFFC80F18694B753E7EB275CD1FB`.

For each row below, record Minecraft/NeoForge/Rovenfall versions, operating system, physical
resolution, selected GUI scale, resulting logical size, locale, resource pack, inventory mod,
operator role, and commit SHA. Capture PNGs with descriptive names such as
`ko_kr-1280x720-scale3-admin-form-focus.png`.

| Case | Required state | Result | Capture |
| --- | --- | --- | --- |
| Minimum | 1280 × 720, GUI scale producing at least 426 × 240 | Pending | Pending |
| Standard | 854 × 480 logical/windowed client | Passed | [Inventory](release-evidence/ui/issue-101/ko_kr-inventory-854x480.png), [administration](release-evidence/ui/issue-101/ko_kr-administration-854x480.png) |
| Large | 2560 × 1440 or larger | Pending | Pending |
| Locale | Repeat standard in `ko_kr`, `en_us`, and `ja_jp` | `ko_kr` passed; `en_us` and `ja_jp` pending | [Korean inventory](release-evidence/ui/issue-101/ko_kr-inventory-854x480.png), [Korean administration](release-evidence/ui/issue-101/ko_kr-administration-854x480.png) |
| Resource pack | Repeat standard with the release-supported pack | Pending | Pending |
| Inventory compatibility | Repeat with each declared compatible inventory mod | Pending | Pending |

For every applicable row:

1. Open the survival inventory and capture all seven tabs, character summary, slots, and focused tab.
2. Open Overview, Journey, Land, Skills, and Shops using keyboard only; page and activate one safe action.
3. Confirm normal details contain no raw UUID, namespaced ID, or long hash. Focus Technical information,
   reveal them, then verify `Ctrl+C` copies the keyboard-focused card details.
4. Open the operator console for each role. Verify role-visible domains, search, paging, selectors,
   typed fields, current-position buttons, preview, cancel, and a non-destructive result panel.
5. Submit search and a typed form with both main Enter and keypad Enter. Trigger one validation
   error and verify it is visible and narrated.
6. Enable Minecraft narration. Verify title, focused control, card position, public detail, usage,
   disabled state, and validation error are intelligible without relying on color or pointer hover.
7. With a declared compatible inventory mod, verify its subclassed/replacement screen is not
   replaced. Verify `/rovenfall menu` and `/rovenfall admin gui` still open the fallback paths.
8. Record any clipping, overlap, unreadable contrast, missing focus, inaccessible action, narration
   omission, or unexpected raw identifier as a release blocker with its exact matrix row.
