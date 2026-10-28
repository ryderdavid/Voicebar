#!/bin/bash
# Does the onboarding need a look? The intro video (video/) and the in-app walkthrough (the setup
# wizard and first-run tips) show the app as it was when they were last reviewed. When the files
# that decide what a user sees have changed since then, this says so.
#
#   tools/onboarding-check.sh            exit 1 and list what changed, when there's something to review
#   tools/onboarding-check.sh --stop     the same as a Claude Code Stop hook (reads its JSON on stdin)
#   tools/onboarding-check.sh --reviewed record that the onboarding was checked against the app as it is now
#
# The record is a fingerprint of those files' contents, kept in tools/onboarding-reviewed.

cd "$(dirname "$0")/.." || exit 0
SRC=app/src/main/java/com/ryderdavid/voicebar
FILES=(
  "$SRC/DockView.kt" "$SRC/PopKeys.kt" "$SRC/Bar.kt" "$SRC/Marks.kt"
  "$SRC/SetupWizard.kt" "$SRC/Tips.kt" "$SRC/SettingsActivity.kt" "$SRC/SettingsUi.kt" "$SRC/VoiceprintUi.kt"
  app/src/main/res/values/strings.xml
)
RECORD=tools/onboarding-reviewed

fingerprint() { for f in "${FILES[@]}"; do [ -f "$f" ] && printf '%s %s\n' "$(shasum -a 256 "$f" | cut -c1-16)" "$f"; done; }

if [ "$1" = "--reviewed" ]; then
  fingerprint > "$RECORD"
  echo "Onboarding marked as reviewed against the app as it is now."
  exit 0
fi

if [ "$1" = "--stop" ]; then
  input=$(cat)
  # already sent back once this turn: don't hold the turn open again
  if printf '%s' "$input" | grep -q '"stop_hook_active"[[:space:]]*:[[:space:]]*true'; then exit 0; fi
fi

[ -f "$RECORD" ] || fingerprint > "$RECORD"
changed=$(diff <(fingerprint) "$RECORD" | grep '^<' | awk '{print $3}' | sort -u)
[ -z "$changed" ] && exit 0

list=$(printf '%s' "$changed" | sed 's|^.*/||' | paste -sd, - | sed 's/,/, /g')
msg="Onboarding check: app-facing files changed since the intro video and in-app walkthrough were last reviewed ($list). Check whether video/ (the intro video's storyboard and scenes) or the setup wizard and first-run tips (SetupWizard.kt, Tips.kt) now show anything out of date. Update them or file the update as a bead (bd), and say what you found in your reply. Then run tools/onboarding-check.sh --reviewed to record it."

if [ "$1" = "--stop" ]; then
  # Claude Code: keep the turn going once, with the reason as the next instruction
  python3 -c 'import json,sys; print(json.dumps({"decision":"block","reason":sys.argv[1]}))' "$msg"
  exit 0
fi
echo "$msg"
exit 1
