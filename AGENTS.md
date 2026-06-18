# AGENTS.md

## Project

ASUP player is a small Android video player written in Kotlin. The current UI is XML-based and uses libVLC.

## Working Rules

- Keep changes scoped and consistent with the existing Kotlin/XML structure.
- Prefer AndroidX and public APIs over custom playback logic.
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
- Manifest: `app/src/main/AndroidManifest.xml`

## Current Behavior

- App starts with a black screen and a centered `Найти видео` button.
- The file picker opens local video files.
- After opening player just plays the video.
