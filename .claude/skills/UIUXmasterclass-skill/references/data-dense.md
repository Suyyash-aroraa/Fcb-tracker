# Data-dense mode: dashboards, stats apps, trackers

taste-skill scopes itself to landing pages and portfolios, while ui-ux-pro-max covers dashboards generically. This file is the bridge: how to make a data product feel designed without turning it into a marketing page or a grey admin template.

## Posture

- The data is the hero. The first viewport answers the user's #1 question (for a sports tracker: *what's next, and how did the last one go?*) without scrolling.
- Default dials: variance 6, motion 4, density 7. Asymmetry happens at the page level (a wide primary column plus a narrow rail), not inside tables.
- Brand identity lives in one or two strong moments (a header field, a stripe, a crest), not on every component.

## Numbers

- `font-variant-numeric: tabular-nums` on every figure that aligns or updates. A mono or tabular face for stat columns.
- Right-align numeric table columns and left-align text. Units in a lighter weight, never a different size.
- Show real precision only: possession `69.5%`, pass accuracy `90%`. Never invent decimals.
- Missing data renders as a quiet `-` with an accessible label ("not available"), never `0`.

## Comparison visuals (home vs away, player vs player)

- Mirrored bars from a shared centre line, with the value printed at each end. No grey background tracks.
- The leading side gets the accent (or its team colour); the trailing side gets a neutral. Always print the number, since colour alone doesn't carry meaning.
- Rates (%) and counts in separate groups. Don't mix a 0-100 bar with a 0-642 bar at the same scale; normalise per row (a / (a + b)).

## Form and results

- W/D/L chips always carry the letter, not only a colour. Win, draw and loss colours must pass 3:1 against the surface in both themes.
- Scores: large condensed display type; the winner's score at full contrast, the loser's at secondary contrast.
- Chronology: the newest result first in "previous", the soonest fixture first in "next".
- Relative time for anything within 7 days ("Sat 16:30, in 2 days"), absolute otherwise, always in the viewer's local timezone with the zone shown once.

## Lineups

- Pitch view for starters, drawn from the formation string (4-3-3, 4-2-3-1), with rows computed from the formation and never hardcoded coordinates.
- Shirt number inside the marker, surname below, a rating badge when available. Substitutes as a compact list with the minute on/off.
- Pitch markings are thin hairlines at low contrast. It's a diagram, not a texture.

## Tables (standings, squad stats)

- Sticky header row; zebra striping *or* hairlines, not both; highlight the tracked team's row with a tinted background and a side bar (not colour alone).
- Horizontal overflow is allowed inside the table container on mobile (with the first column sticky), never on the page.
- Sortable columns are buttons with `aria-sort`.

## Navigation

- 3-6 top-level views as tabs or a segmented control, with URL deep links (hash or path) so back and forward work.
- Match detail is a route, not a modal, so it can be shared.
- Keyboard: arrow keys move between tabs (roving tabindex); Enter/Space activates; focus returns sensibly after navigation.

## Freshness and states

- Always show data freshness ("Updated 12 min ago") and the source. For live matches, an `aria-live="polite"` region for score changes.
- Skeletons match the final layout per region; each region fails independently with an inline error and a retry, never a full-page crash.
- An empty state explains *why* ("No lineups yet. They're published about an hour before kick-off.").

## Motion

- Numbers may count up once on first reveal (reduced-motion: no animation).
- Bars grow from the centre line on reveal (transform: scaleX), staggered by 30-40ms per row.
- View changes: a 150-250ms opacity plus an 8px translate. No page-wide choreography.
