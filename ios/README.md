# lumi (iOS)

The iOS/iPhone port of the lumi NFC music player. It replicates the
Android app's observable behavior for the five core features:

1. **Folder music ingestion** — add a whole folder (its subfolders too)
   with the system folder picker; the access is persisted as a
   security-scoped bookmark (iOS's counterpart of Android's SAF grant),
   and a **rescan** picks up songs added to the folder since the last
   scan (an unchanged folder rescans to nothing; a revoked access makes
   it show as unreadable instead of crashing).
2. **Tag-based browsing** — a tag's page shows its details (name, ID) and
   its song list; the song profile shows the file's artist, album and
   length (best-effort metadata read, 5 s timeout) and the tags the song
   is currently on.
3. **Drag-and-drop ordering** — a tag's song list reorders live by
   dragging; the persisted order is the play order (reorder writes are
   accepted only when they are a permutation of the current list, and
   removals renumber the survivors densely).
4. **NFC proximity play/pause** — the CoreNFC reader session is always
   listening: holding a registered tag against the back of the phone
   starts/switches playback without a tap (an NFC read overrides a
   pause), and removing it in proximity mode pauses after the absence
   window (the "tag re-read wait", 15 s default, 1-120 s clamp). The
   debounce mirrors the Android app: a failed re-read first retries once
   after 100 ms; two failed reads confirm the removal. The on-card
   payload is byte-compatible with the Android app (an
   `application/x-lumi` NDEF record carrying `{"name","songs"}` JSON),
   and the tag page's "Save to NFC tag" section writes that same
   payload to the tag's own tag (its "Write to NFC tag" action, beside
   a live in-sync indicator and a "Scan to compare" read that records
   what the tag's own tag carries as its last saved state - a blank tag
   clears it).
5. **Background-capable playback** — `AVAudioSession` in the `.playback`
   category with the `audio` background mode declared: audio keeps
   playing with the screen locked or the app backgrounded.

Plus the supporting surface the features need: tag registration (the
"Scan NFC" button in the NFC tags pane, a naming dialog with the card's
name/song list pre-filled when it carries a lumi payload or remembered
data), tag↔song links (the in-app content picker with check + save),
the two play modes (continuous / proximity, remembered on the device),
and per-tag playback position memory (LRU 3 tags; a tag whose linked
list changed restarts fresh).

## Layout

```
ios/
  Package.swift            SPM package: LumiCore + LumiApp + LumiCoreTests
  Sources/LumiCore/        pure Swift: the ported logic (no iOS-only imports)
    Models.swift           entries, folders, tags, links, settings (Codable)
    Store.swift            the local store (JSON in Application Support)
    NdefCodec.swift        the application/x-lumi payload (Android-compatible)
    PlaybackPolicy.swift   the play-mode decision logic
    PlaybackPosition.swift the per-tag position memory (decideRestore, LRU 3)
    ContentSort.swift      the title sort (A-Z / Z-A)
    PlayMode.swift         the play-mode parse (stored values)
    TagSync.swift          the save section's in-sync state (the derived
                           compare verdict: in sync / differs / unknown)
    FolderTree.swift       the picked folder's tree (structure, per-level
                           sort, the visible-row count for the collapse)
    FolderScan.swift       the folder walk (pre-order, audio extensions,
                           rescan diff, the self-listing-folder guard)
  Sources/LumiApp/         the iOS-only app (SwiftUI + AVFoundation + CoreNFC)
    AppModel.swift         the orchestration (the Android view-model port)
    PlaybackEngine.swift   AVPlayer + the session + the bad-file loop guard
    NfcController.swift    the always-listening reader + presence monitor
    FolderAccess.swift     security-scoped bookmarks + the folder children
    MetadataReader.swift   the best-effort file metadata read
    LumiApp.swift          the @main app + navigation
    Views/                 the SwiftUI screens
  Tests/LumiCoreTests/     the observable-behavior pins (plain `swift test`)
  packaging/Info.plist     the app's Info.plist (background audio, NFC)
  Dockerfile               the IPA builder (pinned swift:6.4.0-jammy)
  scripts/build-ipa-docker.sh  the build script (-> dist/lumi-ios.ipa)
```

`LumiCore` is deliberately pure (no AVFoundation, no CoreNFC, no
SwiftUI): it compiles and its tests run on a plain Linux/macOS Swift
host. `LumiApp` only builds with an iOS SDK (it is gated behind the
`LUMI_BUILD_APP=1` env var in the package manifest, so host builds never
try to compile it).

## Verifying the core on a host

With a Swift 6 toolchain:

```sh
cd ios
swift build        # builds LumiCore (LumiApp is excluded)
swift test         # the observable-behavior pins
```

## Building the IPA

The build needs **only a Docker daemon** on the build host — no Xcode, no
macOS, no Swift. One required input: the **iOS Swift SDK** directory,
which the image cannot fetch or generate (the iOS platform files are
Apple's). On a Mac:

1. Install the Swift 6 toolchain (Xcode's is fine) and the
   [swift-sdk-generator](https://github.com/swiftlang/swift-sdk-generator)
   tool.
2. Point it at the `iphoneos` platform files
   (`xcrun --sdk iphoneos --show-sdk-path`) and generate an
   **arm64-apple-ios** SDK.
3. Put the generated SDK directory at `ios/sdk` (it contains
   `swiftSDK.json`).

Then, from the repo root:

```sh
./ios/scripts/build-ipa-docker.sh   # -> dist/lumi-ios.ipa
```

Overrides: `LUMI_IOS_SDK_DIR=<dir>` (default `ios/sdk`),
`LUMI_DOCKER_IMAGE=<image>` (default `lumi-ipa-builder:1`),
`OUT_DIR=<dir>` (default `dist`). The script verifies the .ipa's
structure before finishing: `Payload/Lumi.app` with the arm64 Mach-O
binary and an Info.plist that declares the background-audio mode and the
NFC usage.

The result is **unsigned**: iOS requires a code signature to install an
app, and the signature must come from the person installing it. The
normal path without an Apple developer account is a sideload tool
(AltStore, Sideloadly, …): install `dist/lumi-ios.ipa` with it and the
tool re-signs the app with your own (free) Apple ID at install time —
the app's code, resources and Info.plist are untouched by the
re-signing. (A `.p12` + provisioning profile are the alternative if you
do have one; sign the `Payload/Lumi.app` before zipping — the Docker
build intentionally does not accept credentials.)

## Platform-forced differences from the Android app

Documented here and next to the code — behavior the Android app has that
iOS forces to be different:

- **NFC is foreground-only.** CoreNFC reader sessions do not deliver
  tags while the app is backgrounded or the screen is locked (Android's
  foreground dispatch keeps reading then). Consequences: a held tag
  read while backgrounded is picked up on the return to the foreground;
  proximity-mode removal detection runs only while the app is
  foregrounded, and the absence window is re-armed from the carried
  active tag when the app returns (the Android app's resume re-seed -
  a tag that left while backgrounded pauses up to one full window after
  the return, not on the return). Audio itself is unaffected —
  background audio playback is full.
- **The presence read is a low-level NDEF read on the held tag.** The
  Android app's fast path re-reads the tag's tech session (Mifare/NfcA/
  IsoDep) every 300 ms; iOS has no equivalent of a connected tech
  session, so the presence monitor runs the connected tag's own low-
  level NDEF read (`NFCNDEFTag.read` - the same call the write path's
  read side uses) on a 300 ms heartbeat, with the same two-strike
  debounce + absence window. A failed probe does NOT confirm the
  removal on its own: it drops the stale session and re-checks in 100 ms
  steps, bounded, while the system re-detect is in flight - the
  re-detect's own read settles the outcome (a success refreshes the
  presence, so a cold-but-present tag whose RF session dropped re-
  detects within the bound and is not paused; a failed read - or the
  bound running out without a re-detect at all - is the fresh-session
  read failure, the two-strike confirmation), and the re-detected tag's
  read is a presence read only: it never re-dispatches the payload, so a
  probe-originated re-read cannot undo a user pause/stop or settle an
  armed scan. The absence window is the backstop when no live read can
  be held at all.
- **No screen lock / device-admin auto-lock / themes.** Those are
  Android platform features (the device-admin lock, the window-chrome
  themes) with no iOS equivalent; the iOS app keeps the play-mode / NFC
  / content / system settings menu and omits them.
- **Folder access = security-scoped bookmarks, not SAF.** Picking a file
  or folder grants access scoped to that pick; the bookmark is stored
  with the entry/folder and re-asserted when playback or a rescan needs
  it. A revoked pick (the user deletes the file or the folder leaves
  the Files app's scope) surfaces as an unreadable folder, mirroring
  the Android app's revoked-grant behavior.
- **No per-song card download.** The card-side `url` option on the NDEF
  payload is decoded (the payload format stays fully compatible) but
  the v1 iOS app does not offer the download affordance.
- **Persistence is a JSON file** (Application Support) rather than
  Room/SQLite: same data shapes (entries, folders, tags, links,
  settings, per-card memory, position memory), same invariants
  (payload-deduped adds, the dense renumbering on removal, the LRU-3
  position memory), a file-backed store that the app re-reads/rewrites
  atomically.
- **The soft lock is out of scope** for v1 (a UI overlay with no
  platform dependency; the triple-tap logic is small and ported as
  `LumiCore`-ready if asked).

## Known limitations (v1)

- The iOS-only layer (SwiftUI/AVFoundation/CoreNFC) compiles only in
  the Docker build against the iOS SDK; on a host without an iOS SDK
  it is verified by parse-check plus the core tests.
- The on-card NDEF grammar is the Android app's byte for byte,
  written and read through the tag's OWN low-level NDEF read/write
  (`NFCNDEFTag.read`/`.write` with raw bytes) - not CoreNFC's
  high-level message APIs, which neither emit nor parse that shape.
  The record is the AOSP `NdefRecord` short form while the payload is
  under 256 bytes (the `0xD4` header, the 1-byte type length, the 8-bit
  payload length, then the 18-byte MIME type `application/x-lumi`, then
  the payload), and the long form from 256 bytes up to the app's 512-
  byte cap (the `0xC4` header, the 1-byte type length, a 32-bit payload
  length, then the type and the payload) - the AOSP writer writes the
  type field in BOTH forms (there is no typeless long form; the long
  form keeps the 18-byte type with the 4-byte length, exactly what the
  iOS codec writes). The record carries no ID field and its TNF byte is
  4 (MIME media), matching the Android writer; readers - including the
  Android app - match the record by its type field, never the TNF, so
  the byte-exact grammar is the interop contract, pinned by the codec's
  golden byte tests.
