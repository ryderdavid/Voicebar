# Voicebar welcome video

Remotion source for the narrated product video bundled as `app/src/main/res/raw/welcome.mp4`.

The bar in the video is the real one: `RenderVideoTest` (in the app's tests) plays each scene's script
against a live `DockView`, keyed to the narration's words, and films it frame by frame. The Remotion
composition puts those frames on a phone screen with the chat, the text box's contents, fingertips,
the title and captions.

```
npm install
# narration per scene, Eleven v4, with word timings (key in video/.env, which git ignores)
set -a; . ./.env; set +a; node tts.mjs [voiceId] [--only id,id]   # Sarah unless told; --only re-voices just those scenes
node timeline.mjs                       # scene lengths and word timings (estimated without narration)
(cd .. && ./gradlew testDebugUnitTest -Prender --tests '*RenderVideoTest*')   # film the bar (-Pscene=<id>[,<id>…] for some)
node bar-meta.mjs                       # gather what the filming recorded into src/barMeta.json
npx remotion render src/index.ts Welcome out/welcome.mp4 --codec=h264 --crf=23
# smaller for the APK, still crisp: about 4.5 MB for 2:21
ffmpeg -i out/welcome.mp4 -c:v libx264 -preset slow -crf 27 -pix_fmt yuv420p -movflags +faststart -c:a aac -b:a 96k ../app/src/main/res/raw/welcome.mp4
```

Edit the words in `src/script.json`, and the actions keyed to them in `RenderVideoTest.script()`.
Re-film after any change to the narration's timing (each action waits for its word).

The whole refresh (storyboard, narration, filming, tips and wizard) is the `onboarding-refresh` skill in `.claude/skills/`; the onboarding Stop hook starts it when app-facing files change.
