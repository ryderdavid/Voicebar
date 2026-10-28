---
name: onboarding-refresh
description: Refresh Voicebar's onboarding (the narrated intro video, the first-run tips and the setup wizard) to match the app as it is now, storyboard first. Use when the onboarding Stop hook reports app-facing changes, or when asked to update the intro video, walkthrough or tips.
---

# Onboarding refresh

The onboarding is three things, and all three show the app as it really is:

- **The intro video** (`video/`, bundled as `app/src/main/res/raw/welcome.mp4`): narrated scenes where every frame of the bar is the real `DockView`, filmed by `RenderVideoTest` against the narration's words. Never mock the bar up.
- **The first-run tips** (`Tips.kt` and the `tip_*` strings): the tour on the bar, each step pointing at a control by its animation id (`"a|b"` takes the first that's on the bar).
- **The setup wizard** (`SetupWizard.kt` and the `wiz_*` strings).

Run the whole refresh without stopping for approval: the user asked for it to happen whenever the app changes.

## 1. Storyboard

Compare the app now with what the onboarding shows. Read `video/src/script.json`, `RenderVideoTest.script()`, `Tips.kt` and the `tip_*`/`wiz_*` strings, and what changed (the hook names the files; `git log` since the last refresh says what changed in them).

Write the storyboard as an HTML Artifact: every video scene, tip and wizard screen marked keep / changed / new / dropped, with the new narration or text, the old text struck through where it changed, and what the real bar does on screen. Republish the existing storyboard (the "Voicebar Onboarding Storyboard" artifact) when this session has it; otherwise publish a new one. Keep each new scene to one idea, a sentence or two of narration, in the voice of the others.

## 2. The video

From `video/` (its README has the details):

1. Edit `src/script.json` (scene order is the video's order).
2. Re-voice only changed and new scenes, in Sarah's voice: `set -a; . ./.env; set +a; node tts.mjs --only id,id`. The key is in `video/.env`; never print it.
3. `node timeline.mjs`.
4. Script each new or changed scene in `RenderVideoTest.script()`: real taps, drags and holds on the live bar, keyed to the narration with `cue("word")`. Use the DockView test hooks (`testMenuAt`, `testMarksAt`, `testPickerRow`, …), adding one when a control has none.
5. Film every scene (the bar's look changes between versions): `./gradlew testDebugUnitTest -Prender --tests '*RenderVideoTest*'`, then `node bar-meta.mjs`.
6. Look at a few frames of each new or changed scene (`video/public/bar/<id>/NNNN.png`, Read them) before rendering. Check the bar is in frame and doing what the narration says.
7. Render and encode: `npx remotion render src/index.ts Welcome out/welcome.mp4 --codec=h264 --crf=23`, then the ffmpeg line in the README into `app/src/main/res/raw/welcome.mp4`.
8. Extract a still or two from the final video (ffmpeg `-ss`) and look at them.
9. Update the Outro's settings list in `src/Video.tsx` if Settings' rows changed.

## 3. Tips and wizard

Add, change or drop tips in `Tips.Id` (tests use `Tips.Id.entries`, so order and count are free) and their strings; keep each tip to a title and one or two sentences. Update wizard strings that describe the bar.

## 4. Finish

- Run the unit tests and build the release APK.
- `tools/onboarding-check.sh --reviewed`.
- Commit (the video, frames' meta, narration, script, tips, wizard and the reviewed record together), close or update the onboarding bead, and send the user the new video (`SendUserFile`) with a line on what changed.
