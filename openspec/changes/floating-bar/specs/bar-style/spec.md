## ADDED Requirements

### Requirement: One bar layout
The bar SHALL always use the seven-button layout (⋮ · own key · Cursor · ABC · Voice · ⌫ slot · ↵ slot). The three-wide-buttons layout and its held-⌫ row MUST NOT exist, and a saved choice of it MUST be ignored.

#### Scenario: Upgrading from three wide buttons
- **WHEN** someone who used three wide buttons updates
- **THEN** the bar shows the seven buttons with their own keys at the defaults (menu, space, backspace, enter)

### Requirement: Bar style setting
Settings SHALL offer a bar style of Docked or Floating, each shown as a live preview of the bar. Docked MUST be the default and MUST behave exactly as the seven-button bar does today.

#### Scenario: Choosing floating
- **WHEN** the user picks Floating in Settings ▸ Bar and Gboard
- **THEN** the next time the bar shows it is the floating pill

### Requirement: Floating bar
In the floating style the bar SHALL draw the same seven keys at the same height, colors and behavior, inside a pill that hugs them with the bar's own padding and sits clear of the screen edges and the navigation area, plus a narrow collapse key at the far right that appears only in this style. The app MUST be laid out above the pill. Dictation, own keys, the picker, the menu and the cursor pad MUST work as on the docked bar; the menu and pad open above the pill at its width; ABC opens the normal full-width keyboard and closing it returns to the floating pill.

#### Scenario: Dictating on the floating bar
- **WHEN** Voice is tapped on the floating bar
- **THEN** Cursor and ABC slide left, Voice becomes the recording pill and the two keys right of Voice stay, all inside the floating pill

#### Scenario: Collapsing
- **WHEN** the collapse key is tapped
- **THEN** the bar collapses to the record pill and stays collapsed (remembered) until expanded

### Requirement: Collapsed record pill
When collapsed, only a record pill SHALL show, floating over the app, with an expand arrow inside it. The app MUST get the whole screen (no space reserved) and only the pill MUST take touches. Tapping the pill's microphone starts dictating; the pill grows into the recording pill (tap to finish, swipe right to send, swipe left to cancel) and shrinks back afterwards. Tapping the arrow expands the floating bar.

#### Scenario: Touches pass through
- **WHEN** the user taps the app anywhere outside the collapsed pill
- **THEN** the app receives the tap

#### Scenario: Dragging the pill
- **WHEN** the user drags the collapsed pill and lets go
- **THEN** it stays where it was dropped, kept fully on screen, and comes back there next time

#### Scenario: Recording collapsed
- **WHEN** the collapsed pill's microphone is tapped
- **THEN** it grows into the recording pill in place (kept on screen) and returns to the record pill when the dictation ends
