# ASUP player

ASUP player is a minimal Android video player built with Kotlin, XML views, and AndroidX Media3 ExoPlayer.

## Features

- Open local videos from Android's system document picker.
- Supports common video files and MKV/Matroska MIME types exposed by file providers.
- Plays selected videos with Media3 ExoPlayer.
- Indexes embedded subtitle tracks before playback starts so subtitle phrase navigation is ready immediately.
- Restores playback position and play/pause state after activity recreation.
- Keeps playback running across orientation changes.
- Keeps the screen on while the player is visible.
- Uses black video background.
- Handles system bars:
  - portrait mode keeps navigation visible and pads controls above it;
  - landscape mode hides status and navigation bars for fullscreen playback.
- Hides previous/next media buttons.
- Enables subtitle and audio track selection through app-owned popup menus.
- Supports subtitle phrase navigation:
  - double tap left half of the video to jump to the previous subtitle phrase;
  - double tap right half of the video to jump to the next subtitle phrase.
- Single tap toggles player controls after Android confirms it is not part of a double tap.
- Provides a subtitle visibility toggle that hides/shows subtitle rendering without disabling the selected subtitle track.

## Requirements

- Android Studio with the Android Gradle Plugin used by this project.
- JDK compatible with Gradle/AGP in this repo.
- Android SDK for compile/target SDK 36.
- Minimum supported Android version: API 33.

## Build

From the repository root:

```sh
./gradlew :app:assembleDebug
```

The debug APK is generated under:

```text
app/build/outputs/apk/debug/
```

## Project Structure

```text
app/src/main/java/com/lb/asupplayer/MainActivity.kt
app/src/main/java/com/lb/asupplayer/Media3SubtitlePhraseExtractor.kt
app/src/main/java/com/lb/asupplayer/Media3SubtitleTrackOutput.kt
app/src/main/java/com/lb/asupplayer/SubtitleFormatMatcher.kt
app/src/main/java/com/lb/asupplayer/model/
app/src/main/res/layout/activity_main.xml
app/src/main/res/layout/asup_player_control_view.xml
app/src/main/res/values/strings.xml
app/src/main/AndroidManifest.xml
gradle/libs.versions.toml
```

## Implementation Notes

- Local file selection uses `ActivityResultContracts.OpenDocument`.
- The app requests persistable read access for selected document URIs when the provider supports it.
- `PlayerView` uses a custom Media3 controller layout via `app:controller_layout_id`.
- Previous/next media buttons and center rewind/forward buttons are hidden.
- Subtitle indexing uses Media3 extractor internals to read embedded text tracks from MKV/Matroska files.
- The subtitle indexing screen uses an indeterminate progress bar. File-read position is not a reliable progress metric for seekable MKV extraction.
- Subtitle track selection and subtitle visibility are intentionally separate:
  - track selection chooses the active subtitle source and phrase index;
  - visibility only hides or shows `playerView.subtitleView`.
- Double tap seeking is implemented through a `GestureDetector` on `PlayerView`; the raw tap is consumed so Media3 controls do not appear during double tap seeking.
- `MainActivity` handles `keyboardHidden|orientation|screenSize` configuration changes to avoid interrupting playback during rotation.
- Window insets are applied manually because the app uses edge-to-edge layout.

## Known Next Steps

- Add subtitle appearance settings:
  - position;
  - size;
  - style/background;
  - optional override of embedded subtitle styles.
- Save subtitle settings in `SharedPreferences`.
- Consider replacing the playback-finished dialog text with localized Russian strings.
- Consider caching subtitle indexes per URI if startup indexing becomes too slow for large files.

## Commit Style

Use Conventional Commits, for example:

```text
feat: add subtitle appearance settings
fix: preserve controls above navigation bar
style: update player control layout
docs: document project setup
```
