## Context

The ⋮ menu today is a drawer (`Drawer.MENU`) with tabs (dictations, clipboard), a scrolling card row and tiles (Settings, Switch keyboard, cost). The own-key picker is a different mechanism: a column in the see-through room above the bar, with its rect added to the touchable region.

## Decisions

- **The list menu reuses the picker's mechanics** (room above the bar, rows counted from the bottom, tap or slide-and-release, tap outside closes, its rect in the touchable region) with its own rows. One mechanism for every pop-up list, as asked.
- **`Drawer.MENU` stays as the dictations pane**: same cards and gestures, minus the tabs and tiles, and a shorter room.
- **☰ is `Act.MORE` without an own key.** Own slots become three on the docked bar (`SPACE_ALT`, `BACKSPACE`, `ENTER`) and one on the floating pill (its own pref, `bar_key_float`, in the `ENTER` slot so it stays put while dictating).
- **Collapsed:** the touchable region is the pill plus the open list.
- **Clipboard history removal** deletes `ClipHistory`, its listener and its file on start.

## Risks / Trade-offs

- [People who used clipboard pins lose them] → accepted (asked for); Paste still reaches the system clipboard from the bar, the menu and the cursor pad.
- [The keyboard's ⋮ opens the list over the keyboard] → the list anchors to that key's left edge, like ☰.
