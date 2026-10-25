## Why

A floating bar along the bottom still covers a band of the screen. Standing it upright against a side edge keeps most of the app visible and puts the keys under a thumb resting on the phone's side.

## What Changes

- The floating bar's menu gets **Turn it upright** (on the upright bar, **Turn it sideways**); remembered.
- **Upright**: the same keys stacked, round, icons upright: ⠿ · ☰ · Cursor · ABC · Voice · own key. It hugs the nearer side edge (drag ⠿ up, down or across; dropped, it snaps to the nearer edge) and floats over the app, which keeps the whole screen.
- **Recording upright** keeps Voice round: no wide recording pill. A small waveform pulses in it. Pulling Voice up or down stretches it, at most two keys tall, into a pill: past the end, up sends and down cancels; short of it, it springs back. Tap finishes and cleans up. Words go into the box as you speak (with words only in the pill, nothing shows until you finish).
- The menu, own-key choices and cursor pad open beside the column, toward the middle of the screen. Notes show beside Voice. ABC still opens the full-width keyboard.
- **Collapsed upright**: ⠿ · ☰ · Voice in a short column against the edge.
- Out of scope: an upright docked bar, an upright keyboard, landscape-specific layouts.

## Capabilities

### New Capabilities
- `upright-bar`: the upright floating bar, its recording gestures, and where its panels open.

### Modified Capabilities
<!-- none -->

## Impact

`DockView` (upright layout, mic stretch, panel placement, drag and edge snap), `Prefs` (`bar_upright`), menu items, tests and renders; README and product page.
