# lumi

lumi is a children's music player in the spirit of Toonies and Yoto: an
Android app that reads NFC tags and plays the music linked to them. Music
lives on the phone (picked with the standard file picker); there is no
server or account — everything is stored locally on the device.

## How it works

1. In **Settings**, register an NFC tag (hold the back of the phone against
   the tag after tapping "Scan NFC" in the NFC tags pane — the dialog lets
   you name it and pick its preferred play mode, and a writable tag can
   optionally store that on the tag itself so re-registering it later
   pre-fills the form), register content (pick an audio file from the
   device, or a whole folder to add every song in it and its subfolders —
   the list is sorted by title, Z-A by default, and every song row has a
   play button that plays that song directly with no tag attached (a folder
   row opens the folder's own page, where its songs carry the buttons);
   tapping a song opens its profile, which shows the file's artist, album
   and length, the tags the song is currently on, and lets you assign the
   song to a registered tag — by picking one, or by scanning a tag right
   on the page (an unregistered one is registered first, and the song is
   assigned once it is saved)), and link a
   tag to the songs it should play. Scanning a tag that is
   already registered opens its page instead. The NFC tags section lists
   the registered tags, and tapping a tag opens its own page — the tag's
   details (its name, which can be edited, and its read-only ID), its song
   list (songs are added straight onto the tag with one pick, a song can
   be removed from the tag (it stays in the content list), and the list
   can be reordered by dragging a song's handle to set the play order; no
   data is written to the tag), and a danger zone to delete the tag (its
   songs stay in the content list).
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
the last played position is remembered per tag, for the three most
recently played tags.

If the NFC radio is off (or the device has no NFC hardware), lumi shows a
prompt with a shortcut to the NFC settings screen.

## Play modes

Both modes are selectable in Settings and are remembered on the device.

- **Continuous** — reading a tag starts playback and it keeps going (and
  loops the tag's list, even after the tag is removed) until you read a
  *different* tag, which switches to that tag's list. Re-reading the same
  tag does not restart.
- **Proximity** — playback continues only while the tag stays in the phone's
  NFC range; remove the tag and playback pauses, and reading the tag again
  resumes where it left off.

In both modes, re-reading the tag that is playing while you have paused
resumes playback — an NFC read overrides play/pause.

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

## Permissions

- **NFC / NFC NDEF** — reading tags (no runtime prompt; granted with the app).
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

```sh
./gradlew assembleDebug     # build the app
./gradlew testDebugUnitTest # unit tests
./gradlew lintDebug         # lint
```

The first build needs the Android SDK; set `sdk.dir` in `local.properties`
or `ANDROID_HOME`.

### Building the APK with Docker (no Android SDK on the host)

The repo ships a `Dockerfile` with a pinned toolchain (JDK 17, Android
platform 35, build-tools 35.0.0; Gradle 8.13 via the wrapper) and a wrapper
script. All it needs on the host is a Docker daemon:

```sh
./scripts/build-apk-docker.sh   # -> dist/lumi-app-debug.apk
```

Overrides: `LUMI_DOCKER_IMAGE=<image>` (default `lumi-apk-builder:1`),
`OUT_DIR=<dir>` (default `dist`). The first run downloads the Android SDK
(~500 MB) into the image and the Gradle distribution via the wrapper;
subsequent builds reuse the named `lumi-gradle-cache` volume for a fast
incremental build. The host needs no Android SDK or JDK at all.

## Testing NFC without a device

The play-mode decision logic (`PlaybackPolicy`), the remembered-position
logic (`data/PlaybackPosition.kt`), and type parsing are pure and
unit-tested. On a device or emulator with an NFC tag (or a tag emulator app),
register the tag, link an audio file, and use the two modes.
