# ASUP Player

ASUP Player is a minimal Android 12+ video player built with Kotlin and XML views. Playback uses MPV (via `libplayer.so`, `libmpv.so`, and bundled FFmpeg libraries). There is no libVLC dependency.

## Features

- Home screen with a file-open button and a list of recently opened files.
- Resumes from the last saved position when opening a recent file.
- Plays local videos opened through the system document picker.
- MKV embedded subtitle tracks are extracted and cached in the background.
- External SRT subtitle files can be loaded from the settings menu.
- Player controls: seek bar, time display, play/pause, subtitle visibility toggle, settings.
- Settings menu: audio tracks, subtitle tracks (size and vertical position), subtitle tap actions, language, feedback, help.
- Subtitle tap actions (when paused — single-tap, double-tap, long-press): send to installed dictionary/translation apps via `ACTION_PROCESS_TEXT`, copy to clipboard, or show a pick menu.
- Double-tap left half of the screen to seek to the previous subtitle phrase; right half for the next.
- Swipe up/down on the left half of the screen to adjust brightness; right half to adjust volume.
- App language can be switched between system default, English, and Russian.
- Portrait keeps system bars visible; landscape uses immersive fullscreen.
- Keeps the screen on while video is playing.
- Handles orientation changes without recreating the Activity.

## Requirements

- Android Studio with Android SDK platform 36 installed.
- JDK 17.
- Minimum supported device version: Android 12 / API 31.

## Build

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The debug APK is output to:

```
app/build/outputs/apk/debug/
```

## Project Structure

```
app/src/main/java/
  com/lb/asupplayer/
    MainActivity.kt          # single activity: playback, UI, all menus
    RecentFilesStore.kt      # recent files, positions, subtitle caches
    mpv/
      MPVView.kt             # MPV options, transport API (extends BaseMPVView)
    subtitle/
      MkvSubtitleExtractor.kt
      SrtParser.kt
      SubtitleRenderer.kt
      SubtitleTrack.kt
      SubtitleEntry.kt
  is/xyz/mpv/
    BaseMPVView.kt           # SurfaceView → MPV surface wiring
    MPVLib.kt                # JNI bridge to libplayer.so

app/src/main/jniLibs/
  {arm64-v8a,armeabi-v7a,x86,x86_64}/
    libplayer.so             # mpv-android JNI layer
    libmpv.so                # MPV core
    libavcodec.so            # }
    libavformat.so           # } FFmpeg
    libavutil.so             # }
    libavfilter.so           # }
    libavdevice.so           # }
    libswresample.so         # }
    libswscale.so            # }
    libc++_shared.so

app/src/main/res/
  layout/activity_main.xml
  values/strings.xml         # English
  values-ru/strings.xml      # Russian
  drawable/                  # vector icons
```

## Implementation Notes

- Local files are opened with `ContentResolver.openFileDescriptor()` and passed to MPV as `fd://<fd>`. This build of `libplayer.so` does not support `content://` URIs.
- `MPVLib` is a Kotlin `object` (singleton). All MPV state is global.
- `MPVLib.kt` and `BaseMPVView.kt` must stay in the `is.xyz.mpv` package — `libplayer.so` exports JNI symbols as `Java_is_xyz_mpv_MPVLib_*`.
- MPEG-4 Part 2 (DivX/XviD, common in AVI files) uses FFmpeg software decoding. `mpeg4` is excluded from `hwdec-codecs` because MediaCodec cannot reconstruct timestamps for MPEG-4 in AVI containers.
- Two `ParcelFileDescriptor` slots (`currentPfd`, `prevPfd`) keep old file descriptors alive until `MPV_EVENT_END_FILE` confirms MPV has closed the previous stream.
- Subtitle `time-pos` callbacks arrive at ~60 fps via `MPV_FORMAT_DOUBLE`, which gives accurate timing for AVI and other containers.
- The app does not request broad storage permissions.

## Commit Style

Use Conventional Commits:

```
feat: add subtitle appearance settings
fix: preserve controls above navigation bar
refactor: replace VLC with MPV playback engine
docs: update project structure in README
```
