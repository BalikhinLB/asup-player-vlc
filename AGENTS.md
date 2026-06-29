# AGENTS.md

## Project

ASUP Player is an Android 12+ video player written in Kotlin with XML views. Playback is handled by MPV (via bundled `libplayer.so` + `libmpv.so` + FFmpeg shared libraries in `jniLibs`). There is no libVLC dependency.

## Working Rules

- Keep changes scoped and consistent with the existing Kotlin/XML structure.
- Prefer AndroidX and public APIs over custom playback logic.
- Use the system document picker for local files. Do not add broad storage permissions unless the feature truly needs them.
- Files are opened with `ContentResolver.openFileDescriptor()` and passed to MPV as `fd://<fd>`. Do not pass `content://` URIs directly to MPV — this build of `libplayer.so` does not support them.
- `MPVLib.kt` and `BaseMPVView.kt` must stay in the `is.xyz.mpv` package — `libplayer.so` exports JNI symbols under `Java_is_xyz_mpv_MPVLib_*`. Moving these classes will break the JNI link at runtime.
- `MPVLib` is a Kotlin `object` (singleton). All MPV state is global; do not create multiple instances.
- Preserve playback across orientation changes.
- Keep the screen awake only while playback is active.
- Treat system bars and window insets carefully:
  - portrait keeps system bars visible and pads player controls above navigation;
  - landscape uses immersive fullscreen.
- MPEG-4 Part 2 (DivX/XviD, common in AVI) must use software decoding. `mpeg4` is intentionally absent from `hwdec-codecs` in `MPVView.initOptions()`. Do not add it back — MediaCodec cannot reconstruct timestamps for MPEG-4 in AVI containers.
- Before finishing code changes, run:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

- At the end of any change summary, suggest a git commit message that follows Conventional Commits.

## Key Source Files

| File | Purpose |
|---|---|
| `app/src/main/java/com/lb/asupplayer/MainActivity.kt` | Single activity: playback lifecycle, UI, settings menus, subtitle rendering |
| `app/src/main/java/com/lb/asupplayer/RecentFilesStore.kt` | Persists recently opened files, playback positions, subtitle caches |
| `app/src/main/java/com/lb/asupplayer/mpv/MPVView.kt` | Extends `BaseMPVView`; sets MPV options, exposes transport API |
| `app/src/main/java/is/xyz/mpv/BaseMPVView.kt` | Low-level `SurfaceView` that wires the Android surface to MPV |
| `app/src/main/java/is/xyz/mpv/MPVLib.kt` | Kotlin JNI bridge to `libplayer.so` |
| `app/src/main/java/com/lb/asupplayer/subtitle/` | `MkvSubtitleExtractor`, `SrtParser`, `SubtitleRenderer`, `SubtitleTrack`, `SubtitleEntry` |
| `app/src/main/res/layout/activity_main.xml` | Main layout; video surface is `<com.lb.asupplayer.mpv.MPVView>` |
| `app/src/main/res/values/strings.xml` | English strings |
| `app/src/main/res/values-ru/strings.xml` | Russian strings |
| `app/src/main/AndroidManifest.xml` | Manifest |
| `app/build.gradle.kts` | App module Gradle config |
| `gradle/libs.versions.toml` | Dependency versions |

## Current Behavior

- App starts with a home screen: a `Найти видео` button and a list of recently opened files.
- Tapping a recent file resumes from the saved position with cached subtitle tracks.
- After opening a file MPV starts playback via `fd://` file descriptor.
- MKV subtitle tracks are extracted in a background thread; other files play immediately.
- Player controls: seek bar, time display, play/pause, subtitle visibility toggle, settings button.
- Settings menu: audio tracks, subtitle tracks, subtitle size/position, subtitle tap actions, language, feedback, help.
- Subtitle tap actions (single-tap, double-tap, long-press when paused): copy text, send to `ACTION_PROCESS_TEXT` apps, or show a menu.
- Double-tap left/right on the video seeks to the previous/next subtitle phrase.
- Swipe up/down on the left half adjusts screen brightness; right half adjusts media volume.
- Portrait keeps system bars visible; landscape hides them for immersive playback.
- App language can be switched between system default, English, and Russian without restart.

## Current Scope

- This is a focused single-activity player.
- Do not add playlists, network browsing, storage permissions, or advanced playback features unless the current task explicitly asks for them.
