## Why

The bar has two layouts, and only the seven-button one gets attention: it has the four own keys, and it's the one the video and the product page show. The three-wide-buttons layout (still the default for new installs) is drifting out of date. Some people also want the app to keep more of the screen while they talk, which a full-width docked bar can't do.

## What Changes

- **BREAKING**: the three-wide-buttons layout is removed. Everyone gets the seven-button bar. Holding ⌫ for the ↵ · space · ⌫ row goes with it; on the seven-button bar, ⌫ already keeps deleting with a tap then press-and-hold.
- New **Bar style** setting: **Docked** (today's bar, the default) or **Floating**.
- **Floating** is the same seven keys, same sizes and colors, wrapped in a pill that hugs them with the bar's own padding, plus a narrow collapse key at the far right that only exists in this mode. The app sits above the pill, as it does above the docked bar.
- **Collapsed**: only the record pill is left, floating over the app, which gets the whole screen back. A small arrow inside the pill expands it. The pill can be dragged anywhere and remembers where it was left. Recording grows it into the usual recording pill (tap to finish, swipe right to send, left to cancel).
- In floating mode, ⋮ and the cursor pad open above the pill at its width. ABC opens the normal full-width keyboard; pulling it down returns to the floating bar.
- Out of scope: a floating Voicebar keyboard, resizing the pill, transparency settings, floating over Gboard, and changes to hands-free mode.

## Capabilities

### New Capabilities
- `bar-style`: the docked and floating bar styles, collapsing and expanding, dragging the collapsed pill, and the removal of the three-wide-buttons layout.

### Modified Capabilities
<!-- none: no existing specs -->

## Impact

- `DockView` (layout, drawing, touch, window room), `VoicebarService.onComputeInsets` (insets and touchable region per style), `Prefs`, `SettingsActivity` (Bar and Gboard page), `SetupWizard` preview, tips anchored to bar controls.
- Tests and render tests that use the three-wide layout are moved to the seven-button bar; new tests and renders cover floating and collapsed.
- README and the product page describe the bar styles.
