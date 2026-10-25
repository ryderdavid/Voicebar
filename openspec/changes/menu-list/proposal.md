## Why

The ⋮ menu is a panel of tiles and tabs that grew one feature at a time, and the floating bar needs a home for things that change the bar itself (collapse, docked or floating). The clipboard history only saw copies made while Voicebar was on screen, so it missed what people copy in other apps, the case that matters.

## What Changes

- A **menu key (☰)** at the far left of the bar in both styles; it is no longer an own key. The docked bar keeps three own keys (left of Cursor, right of Voice, far right).
- The **floating pill** is ☰ · Cursor · ABC · Voice · one own key (Backspace to start); the narrow collapse key goes. The **collapsed pill** is ☰ · Voice; the expand arrow goes.
- The **menu is a list** rising from ☰, like the own-key picker: a row of edit keys (Undo, Cut, Copy, Paste, Select all), Paste with a preview of what's on the clipboard now, Recent dictations ›, Collapse or Expand (floating), Dock the bar or Floating bar, Switch keyboard, Settings. The keyboard's ⋮ opens the same list.
- **Recent dictations** opens the dictation cards (as today, without tabs or tiles).
- **BREAKING**: the clipboard history is removed (its tab, cards, pins and stored list). Paste always uses the system clipboard, so copies from any app work.
- **Cost this month** leaves the menu; it stays in Settings ▸ Voice typing ▸ Estimated cost.
- Own-key choices: **Docked ↔ floating** is added; Menu is removed (☰ is always there).
- Out of scope: changes to the dictation cards themselves, the cursor pad's chips, and Gboard.

## Capabilities

### New Capabilities
- `bar-menu`: the menu key, the list menu and its items, the dictations pane it opens, the floating and collapsed layouts with the menu key, and the own-key choices.

### Modified Capabilities
<!-- bar-style lives in the unarchived floating-bar change; its collapse key and expand arrow are superseded here -->

## Impact

- `DockView` (menu key, list menu, floating/collapsed layout, dictations pane), `PopKeys` (⋮ opens the list), `Bar` (choices), `Prefs` (`bar_key_menu` removed, `bar_key_float` added), `SettingsActivity` (own-key rows), `VoicebarService` (clipboard history and listener removed; touch region includes the list when collapsed), `ClipHistory` deleted.
- Tests and renders for the menu; README and product page.
