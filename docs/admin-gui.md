# Administration control center

`/rovenfall admin gui` opens the stable, read-only operator entry point. Existing
`/rovenfall admin ...` commands remain available for automation and emergency use.

## Role-visible domains

| Role | Read-only domains |
| --- | --- |
| Viewer | Players, claims, shops, portals, RPG, encounters, audit, alerts, metrics |
| Moderator | Players, claims, RPG, audit, metrics |
| Economy manager | Players, shops, audit, alerts, metrics |
| Content manager | Players, portals, RPG, encounters, audit, metrics |
| Owner | All domains |

Changing or removing an operator role while the menu is open invalidates the current
view immediately. Every click and search submission rechecks the server-owned role,
container identity, and session state.

## Search and bounds

Each domain has a text search field, an `All`/`Attention required` filter, refresh,
and previous/next page controls. Search is case-insensitive over the identifiers and
summary fields shown by that domain. A request is limited to 64 characters, each
page contains at most 36 rows, and a refresh scans at most 1,000 rows from bounded
domain query services. The menu marks a result as truncated when the source exceeded
that scan budget. Results outside that bounded window require the domain command
fallback for a precise target lookup.

The GUI does not mutate domain state. Follow-up management and recovery controls are
implemented by the later administration GUI slices and must continue to call the
same audited domain services as their command fallbacks.
