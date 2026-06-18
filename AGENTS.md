# AGENTS.md

## Project

ASUP player is a small Android 12+ video player skeleton written in Kotlin. The UI is XML-based and playback uses libVLC.

## Working Rules

- Keep changes scoped and consistent with the existing Kotlin/XML structure.
- Prefer AndroidX and public APIs over custom playback logic.
- Use the system document picker for local files. Do not add broad storage permissions unless the feature truly needs them.
- Pass picker `content://` URIs to playback through `ContentResolver` file descriptors; do not pass raw document URIs directly to libVLC.
- Preserve playback across orientation changes.
- Keep the screen awake only while playback is active.
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
- Manifest: `app/src/main/AndroidManifest.xml`
- Gradle dependencies: `gradle/libs.versions.toml`

## Current Behavior

- App starts with a black screen and a centered `Найти видео` button.
- The file picker opens local video files.
- After opening a file, libVLC starts video playback and hides the button.
- Player controls provide seek, play/pause, audio track selection, and subtitle selection.
- Portrait keeps system bars visible; landscape hides system bars for immersive playback.

## Current Scope

- This is the minimal working skeleton.
- Do not add playlists, network browsing, storage permissions, or advanced playback features unless the current task explicitly asks for them.
