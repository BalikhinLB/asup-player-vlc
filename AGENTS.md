# AGENTS.md

## Project

ASUP player is a small Android video player written in Kotlin. The current UI is XML-based and uses Media3 ExoPlayer through `androidx.media3.ui.PlayerView`.

## Working Rules

- Keep changes scoped and consistent with the existing Kotlin/XML structure.
- Prefer AndroidX and Media3 public APIs over custom playback logic.
- Use the system document picker for local files. Do not add broad storage permissions unless the feature truly needs them.
- Preserve playback across orientation changes.
- Treat system bars and window insets carefully:
  - portrait keeps system bars visible and pads player controls above navigation;
  - landscape uses immersive fullscreen.
- Before finishing code changes, run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

- At the end of any change summary, suggest a git commit message that follows Conventional Commits.

## Current Entry Points

- Main activity: `app/src/main/java/com/lb/asupplayer/MainActivity.kt`
- Main layout: `app/src/main/res/layout/activity_main.xml`
- App strings: `app/src/main/res/values/strings.xml`
- Custom Media3 controller layout: `app/src/main/res/layout/asup_player_control_view.xml`
- Manifest: `app/src/main/AndroidManifest.xml`

## Current Behavior

- App starts with a black screen and a centered `Найти видео` button.
- The file picker opens local video files, including MKV where providers expose Matroska MIME types.
- After a video is selected, embedded subtitle tracks are indexed before playback starts. The indexing screen uses an indeterminate progress bar because MKV extractor file positions are not a reliable progress signal.
- Selected videos are played by ExoPlayer after subtitle indexing finishes.
- Previous/next media buttons are hidden.
- Media3 subtitle track selection is enabled through an app-owned popup that matches the custom settings popup style.
- Subtitle phrases can be navigated with double tap:
  - left half: previous subtitle phrase;
  - right half: next subtitle phrase.
- Single tap toggles player controls only after the tap is confirmed, so double tap seeking does not show the controls.
- A separate subtitle visibility button hides/shows rendered subtitles without disabling the selected subtitle track. Phrase navigation continues to use the selected track while subtitles are hidden.
- Playback position and play state are restored if the activity is recreated.
- The player keeps the screen on during playback.

## Notes For Future Work

- Media3's built-in gear/settings menu is mostly private implementation. Extending it directly is fragile.
- The project uses a custom controller layout through `app:controller_layout_id`. Be careful with Media3 private resources in `asup_player_control_view.xml`; `tools:ignore="PrivateResource"` suppresses lint only and does not change runtime behavior.
- Subtitle indexing for embedded MKV subtitles is implemented with Media3 extractor internals:
  - `Media3SubtitlePhraseExtractor`;
  - `Media3SubtitleTrackOutput`;
  - `SubtitleFormatMatcher`;
  - `SubtitlePhraseIndex` / `SubtitleTimeline`.
- Prefer keeping subtitle track selection separate from subtitle visibility. Track selection affects playback/index source; visibility only affects `playerView.subtitleView`.
- For subtitle appearance settings, prefer a separate app-owned settings dialog or popup that applies values to `playerView.subtitleView`.
- Useful subtitle APIs:
  - `SubtitleView.setBottomPaddingFraction(...)`
  - `SubtitleView.setFractionalTextSize(...)`
  - `SubtitleView.setStyle(...)`
  - `SubtitleView.setApplyEmbeddedStyles(...)`
  - `SubtitleView.setApplyEmbeddedFontSizes(...)`
