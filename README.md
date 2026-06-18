# ASUP player

ASUP player is a minimal Android video player built with Kotlin, XML views, and libVLC.

## Features

- Open local videos from Android's system document picker.
- Plays selected videos.

## Requirements

- Minimum supported Android version 12.

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
app/src/main/res/layout/activity_main.xml
app/src/main/res/values/strings.xml
app/src/main/AndroidManifest.xml
gradle/libs.versions.toml
```

## Implementation Notes

- Local file selection uses `ActivityResultContracts.OpenDocument`.
- The app requests persistable read access for selected document URIs when the provider supports it.


## Commit Style

Use Conventional Commits, for example:

```text
feat: add subtitle appearance settings
fix: preserve controls above navigation bar
style: update player control layout
docs: document project setup
```
