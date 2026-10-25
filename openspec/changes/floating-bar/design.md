## Context

`DockView` draws the bar on a canvas and lays out `items` per state; `equalBar` switches between the two layouts. The view's height is the bar plus the navigation inset plus optional see-through "float room" on top (tips, the picker), and `VoicebarService.onComputeInsets` keeps the app where it was and limits touches to the bar plus that room's live parts. That mechanism is what floating and collapsed build on.

## Goals / Non-Goals

**Goals:** one layout; a floating pill that is the docked bar's keys, not a redesign; a collapsed pill that costs the app no space and can be put anywhere.

**Non-Goals:** floating keyboard, resizable pill, floating over Gboard.

## Decisions

- **Remove the layout by fixing `equalBar` to true, then deleting what only the three-wide layout used** (the held-⌫ tray, its layout branch, the layout setting and previews). Smaller, reviewable steps than a rewrite; tests that leaned on the old default move to the seven-button bar.
- **Floating = the same layout in an inset span.** `layoutSpan` already lays the keys out across a width; floating gives it the pill's inner width (screen minus side margins, minus the collapse key) and offsets it, and draws a pill background instead of the full-width strip. Below the pill the view is transparent; the navigation area is left to the app.
- **Collapsed = float room over the whole screen.** The view grows to the window's height, the service reports content and visible insets at the bottom of the window (the app keeps the whole screen) and sets the touchable region to the pill's rect. The pill's position is a fraction of the available area, saved in prefs, clamped on screen.
- **Drag vs tap on the collapsed pill:** a move past touch slop drags; otherwise the arrow or the microphone handles the tap. While recording, horizontal swipes stay send and cancel, so the pill can't be dragged mid-dictation.
- **Recording collapsed** grows the pill to a fixed share of the screen width, toward the screen's center, so it stays on screen wherever the pill is.
- **Tips** are skipped while collapsed (nothing to point at).

## Risks / Trade-offs

- [Apps that draw nothing under their resized window could show black under a floating or collapsed pill] → the floating bar keeps reserving its space; collapsed reserves none by design. Check common chat apps on a phone.
- [A full-height IME window could intercept touches if the touchable region is wrong] → the region is the pill only, covered by a unit test on the computed rect.
- [Removing the default layout changes the bar for new users] → accepted; the seven-button bar is the one that's maintained.

## Migration Plan

`bar_layout` is ignored from this version; nothing to migrate. New prefs: `bar_style` (docked/floating), `bar_collapsed`, `float_x`/`float_y`.

## Open Questions

- Whether the open floating bar should be draggable too (start fixed; revisit after use).
