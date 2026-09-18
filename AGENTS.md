# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Build / test

- `./gradlew assembleDebug` (build), `./gradlew testDebugUnitTest` (unit tests), `./gradlew lintDebug` (lint). All three are green as of v1.
- No system Java/Android SDK: the toolchain lives in `~/.lumi-dev` — export `JAVA_HOME=~/.lumi-dev/jdk` (JDK 17) and the SDK is at `~/.lumi-dev/android-sdk` (platform 35, build-tools 35.0.0); `local.properties` already points `sdk.dir` there.

## Sharp edges

- Proximity mode: `NfcAdapter.ReaderCallback` only reports `onTagDiscovered` (tag re-reports while the tag stays in range); "tag removed" is derived from a presence timeout in `ui/MainActivity.kt` (`PRESENCE_TIMEOUT_MS`). Don't expect an `onTagLost` callback.
- Android 15 renamed the media foreground-service type to `mediaPlayback` (manifest + `PlayerService.startForegroundWithNotification`); the old `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA` constant is gone from the SDK-35 stubs.
- Entry types are extensible: `data/EntryType.kt` (audio implemented; video/url are representable but their action is an intentional "coming soon" placeholder — see `PlayerService` COMING_SOON handling). New types should keep `fromStorage`'s fallback behavior.
- Audio files are picked via SAF; read grants are persisted per file (`LumiViewModel.addFileEntry`). No storage permissions are used.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
