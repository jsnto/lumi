# lumi

lumi is a children's music player in the spirit of Toonies and Yoto: an
Android app that reads NFC tags and plays the music linked to them. Music
lives on the phone (picked with the standard file picker); there is no
server or account — everything is stored locally on the device.

## How it works

1. In **Settings**, register an NFC tag (hold the back of the phone against
   the tag), register content (pick an audio file from the device, or add a
   video/link entry for future use), and link a tag to one or more entries in
   the order you want them played.
2. On the **front screen**, tap a registered tag against the phone. lumi
   looks up the tag's content list and starts playing, with a big friendly
   animated visual showing what is up.

## Play modes

Both modes are selectable in Settings and are remembered on the device.

- **Continuous** — tapping a tag starts playback and it keeps going (and
  loops the tag's list) until you tap a *different* tag, which switches to
  that tag's list. Re-tapping the same tag does not restart.
- **Proximity** — playback continues only while the tag stays in the phone's
  NFC range; remove the tag and playback stops.

## Content types

A tag links to an ordered list of entries. Each entry has a type:

| Type  | v1 status                                                        |
|-------|------------------------------------------------------------------|
| Audio | Fully supported — plays in the background with a notification.   |
| Video | Representable and registrable now; playback is a "coming soon" placeholder. |
| URL   | Representable and registrable now (e.g. a YouTube link); opening is a "coming soon" placeholder. |

The data model stores `type` + `payload` (file URI for audio/video, URL for
links), so adding the video/URL actions later does not require reworking
storage or the registration UI.

## Tech

- Kotlin + Jetpack Compose (Material 3), minSdk 26, target/compile SDK 35
- Room for the local database (tags, entries, ordered tag→entry links, settings)
- Media3/ExoPlayer in a foreground media service for background playback
- NFC via foreground dispatch (continuous mode, tag registration) and reader
  mode (proximity mode, which also reports tag removal)

## Permissions

- **NFC / NFC NDEF** — reading tags (no runtime prompt; granted with the app).
- **POST_NOTIFICATIONS** — asked on first launch (Android 13+); playback
  notification shown when granted.
- **Foreground service (media)** — keeps audio playing in the background.
- No storage permissions: audio/video files are picked with the system
  document picker (SAF), and the read grant is persisted per file.

## Building

```sh
./gradlew assembleDebug     # build the app
./gradlew testDebugUnitTest # unit tests
./gradlew lintDebug         # lint
```

The first build needs the Android SDK; set `sdk.dir` in `local.properties`
or `ANDROID_HOME`.

## Testing NFC without a device

The play-mode decision logic (`PlaybackPolicy`) and type parsing are pure and
unit-tested. On a device or emulator with an NFC tag (or a tag emulator app),
register the tag, link an audio file, and use the two modes.
