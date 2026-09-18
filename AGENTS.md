# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Build / test

- `./gradlew assembleDebug` (build), `./gradlew testDebugUnitTest` (unit tests), `./gradlew lintDebug` (lint). All three are green as of v1.
- No system Java/Android SDK: the toolchain lives in `~/.lumi-dev` — export `JAVA_HOME=~/.lumi-dev/jdk` (JDK 17) and the SDK is at `~/.lumi-dev/android-sdk` (platform 35, build-tools 35.0.0); `local.properties` already points `sdk.dir` there.

## Sharp edges

- NFC is always listening: both play modes run `NfcAdapter` reader mode (`MainActivity.reconfigureNfc`); a tag read starts/switches playback without a tap and overrides a user pause (`PlaybackPolicy.onTagDetected(uid, paused)` → `NfcAction.Resume` → `PlayerService.ACTION_TOGGLE`).
- Proximity "tag removed" is derived, not reported: `NfcAdapter.ReaderCallback` only reports `onTagDiscovered` (tag re-reports while in range); the presence watcher (`PRESENCE_TIMEOUT_MS` in `ui/MainActivity.kt`) stops playback on absence and is posted only in proximity mode — continuous mode keeps playing after the tag leaves. Don't expect an `onTagLost` callback.
- FGS type: the SDK-35 stubs expose only `ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` (value 0x2), so the manifest declares `android:foregroundServiceType="mediaPlayback"` and `PlayerService.startForegroundWithNotification` requests that type. Do not "fix" the manifest to a `media` spelling: `media` is not a valid flag for the SDK-35 AAPT (a dual `media | mediaPlayback` spelling fails the build).
- Entry types are extensible: `data/EntryType.kt` (audio implemented; video/url are representable but their action is an intentional "coming soon" placeholder — see `PlayerService` COMING_SOON handling). New types should keep `fromStorage`'s fallback behavior.
- Audio files are picked via SAF; read grants are persisted per file (`LumiViewModel.addFileEntry`). No storage permissions are used.
- Compose: `Modifier.shadow` imports from `androidx.compose.ui.draw` (it ships in the `ui` artifact, not `foundation`); an `androidx.compose.foundation.shadow` import does not resolve.
- NFC prompt: `MainActivity.refreshNfcState` keeps `showNfcPrompt` in sync with NFC availability — raised on resume / when registration starts with the radio missing or off, and cleared once it is usable again (e.g. after the user enables the radio and returns); the dialog (with an `ACTION_NFC_SETTINGS` button) is rendered in `LumiRoot` so it overlays any screen. Don't move the prompt into `SettingsScreen` — the player screen also needs it.

## Maintaining this file

Keep this file for knowledge useful to almost every future agent session in this project.
Do not repeat what the codebase already shows; point to the authoritative file or command instead.
Prefer rewriting or pruning existing entries over appending new ones.
When updating this file, preserve this bar for all agents and keep entries concise.
