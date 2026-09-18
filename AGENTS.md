# Project agent memory

This file is the project's committed home for project-intrinsic agent knowledge: build, test, release, architecture, and sharp-edge notes that should travel with the code.

- Add durable project-specific notes here as they are discovered through real work.

## Build / test

- `./gradlew assembleDebug` (build), `./gradlew testDebugUnitTest` (unit tests), `./gradlew lintDebug` (lint). All three are green as of v1.
- No system Java/Android SDK: the toolchain lives in `~/.lumi-dev` — export `JAVA_HOME=~/.lumi-dev/jdk` (JDK 17) and the SDK is at `~/.lumi-dev/android-sdk` (platform 35, build-tools 35.0.0); `local.properties` already points `sdk.dir` there.

## Sharp edges

- NFC is always listening: in both play modes `MainActivity.reconfigureNfc` runs `NfcAdapter` foreground dispatch (self PendingIntent → `onNewIntent` → `handleNfcIntent` → `handleTagDetected`), so a tag read starts/switches playback without a tap and overrides a user pause (`PlaybackPolicy.onTagDetected(uid, paused)` → `NfcAction.Resume` → `PlayerService.ACTION_RESUME`). Do NOT "improve" this back to reader mode: on the real device `onTagDiscovered` never fired where foreground dispatch demonstrably works (tag registration always used it), so foreground dispatch is the single proven receive path. Tag intents are consumed after handling (EXTRA_TAG removed) so a stale tag is not reprocessed on a later resume.
- The manifest `TECH_DISCOVERED` filter is dead without its `android.nfc.action.TECH_DISCOVERED` tech-list meta-data (`res/values/nfc_tech_list.xml`); the framework silently skips such filters, so keep both in sync. That intent path only matters for tags tapped while the app is closed (foreground dispatch wins while the app is in the foreground); the always-listening path is foreground dispatch, not this filter.
- Main-page NFC behavior is pinned by `NfcAlwaysListeningUiTest` (Robolectric): it delivers tags as `EXTRA_TAG` intents via `ActivityController.newIntent` — the same shape the system's foreground dispatch delivers — driving the real `onNewIntent` path with reflectively-built `Tag`s (the stub SDK's `Tag` constructor throws and omits the test hooks), and records dispatch state in `NfcTestState` via `ShadowNfcAdapterForTest`.
- Proximity "tag removed" is derived, not reported: there is no tag-lost callback and foreground dispatch may deliver a stationary tag only once per presentation, so the presence watcher (`PRESENCE_TIMEOUT_MS` in `ui/MainActivity.kt`) treats a short absence of reports on the ACTIVE tag as removal and is posted only in proximity mode — continuous mode keeps playing after the tag leaves. "No further reports" is the only signal, so a held-still tag may trigger the timeout early; stop-on-removal timing under foreground dispatch is the first on-device validation item. The presence record is updated only for the active registered tag (`uid == viewModel.activeTagUid`); reads of other tags must never touch it.
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
