## ADDED Requirements

### Requirement: Turning the floating bar upright
The floating bar's menu SHALL offer Turn it upright, and the upright bar's menu Turn it sideways. The choice MUST be remembered and MUST NOT appear on the docked bar.

#### Scenario: Turning upright
- **WHEN** the user taps Turn it upright
- **THEN** the bar becomes a column of ⠿ · ☰ · Cursor · ABC · Voice · own key against a side edge, floating over the app

### Requirement: Upright placement
Dragging ⠿ SHALL move the upright bar anywhere; it MUST stay where it's dropped, fully on screen, except within 48 dp of a side edge, where it snaps against that edge. Its place MUST be remembered. The app MUST keep the whole screen, and only the column and what's open from it take touches.

#### Scenario: Dropped in the middle
- **WHEN** the user drops the column in the middle of the screen
- **THEN** it stays there

#### Scenario: Dropped near an edge
- **WHEN** the user drops the column within 48 dp of a side edge
- **THEN** it snaps against that edge

### Requirement: Recording upright
While dictating upright, Voice SHALL stay round with a small, non-scrolling waveform that follows the voice level. Pulling it up or down MUST stretch it in that direction to at most two keys tall; released at full stretch, up sends and down cancels; released short of it, it springs back. A tap MUST finish and clean up. Words go into the box as spoken when that setting is on; otherwise nothing shows until the dictation finishes.

#### Scenario: Pull up to send
- **WHEN** the user pulls the recording Voice up to full stretch and lets go
- **THEN** the dictation is finished and sent

#### Scenario: Short pull
- **WHEN** the user lets go before full stretch
- **THEN** Voice springs back and the dictation goes on

### Requirement: Panels beside the column
The menu, own-key choices and cursor pad SHALL open beside the column, toward the middle of the screen, kept on screen. Notes SHALL show beside Voice. ABC MUST open the full-width keyboard.

#### Scenario: Menu from ☰
- **WHEN** ☰ is tapped on the upright bar against the right edge
- **THEN** the menu opens to the column's left, level with ☰ where it fits
