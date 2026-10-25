## ADDED Requirements

### Requirement: Menu key
The bar SHALL show a menu key (☰) at its far left in both styles, and in the collapsed pill beside Voice. It MUST NOT be configurable. The docked bar's other keys are an own key, Cursor, ABC, Voice and two own keys; the floating pill's are Cursor, ABC, Voice and one own key.

#### Scenario: Docked bar
- **WHEN** the docked bar is at rest
- **THEN** it shows ☰ · own key · Cursor · ABC · Voice · own key · own key

#### Scenario: Floating and collapsed
- **WHEN** the floating bar is at rest, and when it is collapsed
- **THEN** it shows ☰ · Cursor · ABC · Voice · own key (Backspace unless changed), and collapsed ☰ · Voice

### Requirement: List menu
Tapping ☰ (or the keyboard's ⋮) SHALL open a list above it, like the own-key picker: a row of edit keys (Undo, Cut, Copy, Paste, Select all, with Cut and Copy dimmed when nothing is selected), Paste showing the start of what's on the clipboard now, Recent dictations, Collapse (floating) or Expand (collapsed), Dock the bar (floating) or Floating bar (docked), Switch keyboard and Settings. A tap outside closes it. It MUST NOT show the cost.

#### Scenario: Pasting from another app
- **WHEN** text was copied in another app and the user taps Paste in the menu
- **THEN** that text goes in at the cursor

#### Scenario: Changing style from the menu
- **WHEN** the user taps Floating bar in the docked bar's menu
- **THEN** the bar becomes the floating pill and the style is saved

#### Scenario: Recent dictations
- **WHEN** the user taps Recent dictations
- **THEN** the dictation cards open (paste, paste as heard, clean again, delete), with no clipboard tab

### Requirement: No clipboard history
Voicebar SHALL NOT keep a clipboard history. Any history saved by an earlier version MUST be deleted.

#### Scenario: Upgrading
- **WHEN** a version with clipboard history updates
- **THEN** its stored clips are removed and no clipboard tab appears

### Requirement: Own-key choices
Every own key SHALL offer Docked ↔ floating, which switches the bar's style when pressed. Menu MUST NOT be a choice. A saved Menu choice falls back to the slot's default.

#### Scenario: Switching style from an own key
- **WHEN** an own key set to Docked ↔ floating is pressed on the docked bar
- **THEN** the bar becomes floating, and pressing it again on the floating pill docks it
