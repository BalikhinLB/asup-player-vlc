# ASUP player

ASUP player is a minimal Android 12+ video player skeleton built with Kotlin, XML views, and libVLC.

The current goal is a working base app that can be extended with player controls and additional playback features later.

## Features

- Starts on a black screen with a centered `Найти видео` button.
- Opens local videos through Android's system document picker.
- Plays the selected video with libVLC.
- Shows minimal playback controls: seek bar, play/pause, audio track menu, and subtitle track menu.
- Keeps the screen on while video is playing.
- Keeps the current Activity alive across orientation changes.
- Keeps system bars visible in portrait and switches to immersive fullscreen in landscape.

## Requirements

- Android Studio with Android SDK platform 36.1 installed.
- JDK 17.
- Minimum supported device version: Android 12 / API 31.

## Build

From the repository root:

```sh
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

For APK-only builds:

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
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
app/src/main/AndroidManifest.xml
app/build.gradle.kts
gradle/libs.versions.toml
```

## Implementation Notes

- Local file selection uses `ActivityResultContracts.OpenDocument`.
- The app requests persistable read access for selected document URIs when the provider supports it.
- Picked `content://` videos are opened through `ContentResolver.openAssetFileDescriptor` before being passed to libVLC.
- Playback is intentionally minimal: the app opens a file, starts playback, and exposes only core controls.
- Orientation changes are handled by `MainActivity` through manifest `configChanges`; the selected URI and playback position are also saved for basic Activity recreation recovery.
- The app does not request broad storage permissions.

## Commit Style

Use Conventional Commits, for example:

```text
feat: add subtitle appearance settings
fix: preserve controls above navigation bar
style: update player control layout
docs: document project setup
```
