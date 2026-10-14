# lumi

lumi is a children's music player: an app that reads NFC tags and plays
the music linked to them. Music lives on
the phone (picked with the standard file picker); there is no server or
account — everything is stored locally on the device. The Android app
(`android/`) is the original; the iOS/iPhone app (`ios/`) ports its core
features to CoreNFC + AVFoundation + SwiftUI and builds its .ipa through
Docker (see the Building section and the ios/ README).

## First time

A fresh install walks a short setup before the player: importing a music
folder (the one required step — the same folder import the content pane
uses), then the optional items, each skippable — playing with an NFC tag
("Set up now" opens the settings screen's NFC tags pane straight away) and,
on Android only, allowing the screen lock (the platform's own consent —
iOS's wizard offers the NFC step as its only optional item). Finishing is
remembered on the device; a returning user goes straight to the player.

## How it works

1. In **Settings**, register an NFC tag (hold the back of the phone against
   the tag after tapping "Scan NFC" in the NFC tags pane — the dialog lets
   you name it; registration never writes to the tag, and a tag
   previously written with the tag's data (the tag page's "Save to NFC
   tag" section) pre-fills the name and restores its saved song list),
   register content (pick an audio file from the
   device, or a whole folder to add every song in it and its subfolders —
   the list is sorted by title, A-Z by default, and every song row has a
   play button that plays that song directly with no tag attached (a folder
   row opens the folder's own page, where its songs carry the buttons);
   tapping a song opens its profile, which shows the file's artist, album
   and length, the tags the song is currently on, and lets you assign the
   song to a registered tag — by picking one, or by scanning a tag right
   on the page (an unregistered one is registered first, and the song is
   assigned once it is saved)), and link a
   tag to the songs it should play. Scanning a tag that is
   already registered opens its page instead. The NFC tags section lists the registered
   tags (in name order, case-insensitive), and tapping a tag opens its
   own page — the tag's
   details (its name, which can be edited, and its read-only ID; when the
   tag's last read carried card information, its tech list and — for an
   NDEF card — its type, capacity and writability are shown as well), its
   song list (songs are added straight onto the tag — the add-song list
   offers the app's existing content in the same folder-tree layout as
   the content folder pages (the individual songs at the top level and
   the added folders as show/hide rows carrying their imported songs),
   and saving the checked selection adds the checked songs at once — a
   song can be removed from the tag (it stays in the content list), and
   the list can be reordered by dragging a song's handle to set the play
   order; a song that is not in the content list can be downloaded from
   its row when the tag's card carries a download link for it — the file
   lands in the device's Music folder's lumi subfolder, with a per-row
   progress while it downloads and a visible retryable failure state;
   managing the list never writes to the tag), a "Save to NFC tag"
   section — the section shows whether the tag's data matches its last
   saved state (in sync / differs / nothing saved or compared yet), a
   "Scan to compare" action that reads the tag's own tag and updates the
   last saved state to what the tag actually carries (a blank tag clears
   it), and a "Write to NFC tag" action that writes the tag's data (its
   name and song list) to the tag's own tag — only a tag whose NFC ID
   matches the tag's is written, a mismatched tag is reported and left
   untouched, and a tag that can't take it (not NDEF, read-only, or too
   small) is reported with nothing written — and a danger zone to
   delete the tag (its songs stay in the content list). The write button
   is greyed out with a note while the tag's data is too large for the
   tag payload, while the phone's own NFC is unusable (no NFC hardware,
   or the radio off), or while the tag's own tag is known not to be
   writable (not an NDEF tag, or read-only) — the compare button only
   needs the NFC radio (a read-only or blank tag is a legitimate compare
   result) — and a write that lands, or a compare read, remembers the
   tag's name and song list, so re-scanning a tag whose tag was deleted
   pre-fills its name and restores its songs.
2. On the **front screen**, hold a registered tag against the phone (no tap
   needed — the NFC reader is always listening). lumi looks up the tag's
   content list and starts playing, with a big friendly animated visual
   showing what is up.

The front screen can be soft-locked with the lock button (top right): the
screen darkens while the app underneath keeps working — music keeps playing.
A one-time popup first explains how to unlock and offers a
"Don't show this again" checkbox. Unlock with a fast triple-tap: three
taps in quick succession (each within about half a second of the
previous one); taps that are too far apart restart the count.

If a tag has been played before, lumi resumes where that tag left off —
the last played position is remembered per tag, for the most recently
played tags (how many, three by default, is the "Remembered playback
positions" option in Settings → Advanced options).

If the NFC radio is off (or the device has no NFC hardware), lumi shows a
prompt with a shortcut to the NFC settings screen.

## Play modes

Both modes are selectable in Settings and are remembered on the
device; with no stored choice the app falls back to proximity (a stored
choice always wins).

- **Continuous** — reading a tag starts playback and it keeps going (and
  loops the tag's list, even after the tag is removed) until you read a
  *different* tag, which switches to that tag's list. Re-reading the same
  tag does not restart.
- **Proximity** — playback continues only while the tag stays in the phone's
  NFC range; remove the tag and playback pauses, and reading the tag again
  resumes where it left off.

In both modes, re-reading the tag that is playing while you have paused
resumes playback — an NFC read overrides play/pause.

## Audio level

The **Audio level** row (Settings → Advanced options) can bring every
song to a common loudness. The choice is remembered on the device:

- **Off** (the default) — songs play at the level they were recorded at;
  no gain is applied. Off is the default, so an existing user's volume is
  never changed.
- **Normalize** — brings each measured song to the target level: quiet
  songs up, loud songs down. The target is picked from -18, -16 (the
  default) or -14 LUFS.

Each audio file's loudness is measured on the device (ITU-R BS.1770 /
EBU R128) when the file is added, and the result is stored with the file
— the file itself is never modified, no normalized copies are made, and
nothing leaves the phone. Measurement is best-effort: a file that can't
be measured plays at its recorded level, and the pane's **Normalize now**
action re-measures every unmeasured or changed file (one decode at a
time), keeping going past a per-file failure.

With Normalize on, the player applies a per-track gain (a custom Media3
audio processor in the playback chain) so a measured song lands at the
target level: a boost is capped by the track's peak headroom and
soft-limited so it never clips, and a song with no measurement plays at
its recorded level. A change to the mode or the target applies from the
next track.

## Content types

A tag links to an ordered list of entries. Each entry has a type:

| Type       | v1 status                                                              |
|------------|------------------------------------------------------------------------|
| Audio      | Fully supported — plays in the background with a notification.         |
| Video / URL | Kept in the data model so existing rows still parse, but not offered in the UI; playback skips them. |

The data model stores `type` + `payload` (file URI for audio), so re-adding
the video/URL actions later does not require reworking storage.

## Tech

- Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compile SDK 35
- Room for the local database (tags, entries, ordered tag→entry links,
  settings, remembered playback positions)
- Media3/ExoPlayer in a foreground media service for background playback
- NFC via foreground dispatch in both play modes (passive, always-listening
  tag reads), also used while registering a tag. In proximity mode, tag
  removal is derived from a 1-second presence poll with a 15-second absence
  grace window (range 1-120 s; the user-facing "Tag re-read wait" setting
  was removed, so the window is no longer configurable in the app): playback
  pauses only once the active tag has gone unreported for that long; tags
  with a connectable read session (Mifare Ultralight, NfcA, or IsoDep)
  additionally get a continuous-read fast path that confirms removal in
  ~0.4 s (see `MainActivity`, `MifarePresencePoller`, `GenericPresencePoller`)
- iOS: Swift (SwiftPM) — a pure `LumiCore` layer (the ported logic: the NDEF
  payload codec, the play-mode policy, the position memory, the content
  sort, the folder walk, the loudness meter and gain math, the JSON store)
  + a `LumiApp` target (SwiftUI +
  AVFoundation + CoreNFC, iOS-only) + `LumiCoreTests` (the
  observable-behavior pins). The NFC payload on the card is byte-compatible
  with the Android app (an `application/x-lumi` MIME NDEF record), so tags
  written by either app work in both; see the ios/ README for the
  platform-forced differences (NFC is foreground-only on iOS, folder access
  is security-scoped bookmarks, the persistence is a JSON file, and the
  screen-lock/theme Android features have no iOS equivalent).

## Permissions

- **NFC / NFC NDEF** — reading tags (no runtime prompt; granted with the app).
- **INTERNET** — downloading a song from a tag card's download link
  (no runtime prompt; granted with the app).
- **POST_NOTIFICATIONS** — asked on first launch (Android 13+); playback
  notification shown when granted.
- **Foreground service (media)** — keeps audio playing in the background.
- No storage permissions: audio files are picked with the system
  document picker (SAF) — a single file or a whole folder tree — and the
  read grant is persisted per file / folder. Re-adding a file or folder
  already in the list adds no duplicates. An added folder can be opened from
  its row (Settings → Content → Folders): the folder's page shows the
  folder's details and the songs imported from it, shown as the folder
  structure the import walked (subfolders nested with left-side connector
  lines, indented one step per depth, with the shared title sort applied at
  each level; a subfolder's row hides or shows the rows nested under it),
  with every song row carrying a play/pause button (the current song pauses
  and resumes in place; any other song starts just that one), and a rescan
  that picks
  up songs added to the folder or its subfolders since the last scan
  (rescanning an unchanged folder adds nothing) and a danger zone that
  removes the folder from the app: after a confirmation that names the
  effect, the folder and the songs imported from it leave the content list
  while the music files themselves stay on the phone.

## Building

The Android project lives under `android/`; run the Gradle wrapper from inside
that directory (the Gradle client resolves the project from the current working
directory, not the wrapper's location):

```sh
cd android
./gradlew assembleDebug     # build the app
./gradlew testDebugUnitTest # unit tests
./gradlew lintDebug         # lint
```

The first build needs the Android SDK; set `sdk.dir` in `android/local.properties`
or `ANDROID_HOME`.

### Building the APK with Docker (no Android SDK on the host)

The repo ships an `android/Dockerfile` with a pinned toolchain (JDK 17, Android
platform 35, build-tools 35.0.0; Gradle 8.13 via the wrapper) and a wrapper
script. All it needs on the host is a Docker daemon:

```sh
./scripts/build-apk.sh   # -> android/dist/lumi-app-debug.apk
```

Overrides: `LUMI_DOCKER_IMAGE=<image>` (default `lumi-apk-builder:1`),
`OUT_DIR=<dir>` (default `android/dist`). The first run downloads the Android SDK
(~500 MB) into the image and the Gradle distribution via the wrapper;
subsequent builds reuse the named `lumi-gradle-cache` volume for a fast
incremental build. The host needs no Android SDK or JDK at all.

### Building the iOS app with Docker (no Xcode on the host)

The iOS project lives under `ios/`; the IPA build needs **only a Docker
daemon** on the build host plus one input Docker cannot provide: the
**iOS Swift SDK** directory (a swift-sdk-generator output over the
`iphoneos` platform files — the platform files are Apple's, so they must
be produced on a Mac; see the ios/ README for the walkthrough). With the
SDK in place (default `ios/sdk`):

```sh
./ios/scripts/build-ipa-docker.sh   # -> dist/lumi-ios.ipa
```

Overrides: `LUMI_IOS_SDK_DIR=<dir>` (default `ios/sdk`),
`LUMI_DOCKER_IMAGE=<image>` (default `lumi-ipa-builder:1`), `OUT_DIR=<dir>`
(default `dist`). The image (pinned `swift:6.4.0-jammy`) builds the
arm64-apple-ios release executable and assembles the structurally valid
`Payload/Lumi.app` .ipa, then the script verifies the structure (see the
ios/ README for what it checks). The result is unsigned: a sideload tool
(AltStore, Sideloadly, …) re-signs it with your own Apple ID at install
time — the app's code and Info.plist are untouched by the re-signing.

On a host with a Swift toolchain (macOS or Linux), the pure core layer
builds and its tests run without any iOS SDK:

```sh
cd ios
swift build   # builds LumiCore (the app target is excluded)
swift test    # the observable-behavior pins
```

## Testing NFC without a device

The play-mode decision logic (`PlaybackPolicy`), the remembered-position
logic (`data/PlaybackPosition.kt`), and type parsing are pure and
unit-tested. On a device or emulator with an NFC tag (or a tag emulator app),
register the tag, link an audio file, and use the two modes.
