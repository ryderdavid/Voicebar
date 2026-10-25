## Context

The floating pill is laid out in bar coordinates from `barTop`; the open pill already floats anywhere (full-screen view, touch region from `floatingInsets`). Upright reuses that: `barTop` is the column's top and the column runs downward.

## Decisions

- **Column layout** replaces the row when upright: keys 44 dp round at fixed places; nothing slides while dictating (Cursor and ABC take the quieter tone).
- **Vertical swipe** reuses the swipe state with a vertical offset; the mic's rect stretches toward the pull, capped at one key plus a gap; armed at the cap.
- **Side placement** for the menu, picker, pad and notes: beside the column toward the screen's middle, top level with their key, clamped to the screen.
- **Edge snap** stores the side as the x fraction (0 or 1) and the center's y fraction.

## Risks / Trade-offs

- [Notes and the "voice bar" pill need text room] → shown beside Voice, like the menu.
- [A long column on a short landscape screen] → clamped; the column is about 300 dp tall.
