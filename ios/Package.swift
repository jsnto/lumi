// swift-tools-version: 6.0
// The lumi iOS app.
//
// The package builds two targets:
//
// - `LumiCore` - the pure logic layer: the NDEF tag payload codec, the NFC
//   playback policy, the remembered-position decision, the folder scan
//   walk, the content sort, and the persisted store with the Android app's
//   ordering semantics. No iOS frameworks: it compiles and unit tests on
//   any platform (the host CI runs it with a plain Swift toolchain).
// - `LumiApp` - the SwiftUI app (iPhone): the front screen, the settings
//   panes, the playback engine (AVFoundation) and the NFC proximity
//   monitor (CoreNFC). It imports SwiftUI/AVFoundation/CoreNFC - iOS-only -
//   so it is omitted on non-Apple build hosts, where only the core layer
//   (and its tests) build; the iOS SDK build (the Docker pipeline)
//   compiles it for arm64-apple-ios.
import Foundation
import PackageDescription

// The SwiftUI app target is included only when the build is the iOS one:
// `LUMI_BUILD_APP=1` (the Docker pipeline's env, the captain's Mac iOS-SDK
// build). Plain `swift build` / `swift test` on a dev host builds and tests
// the core layer only (no SwiftUI/AVFoundation/CoreNFC on Linux, and no
// need for them to run the logic tests).
let buildAppTarget = ProcessInfo.processInfo.environment["LUMI_BUILD_APP"] == "1"
let appTargets: [Target] = buildAppTarget
    ? [
        .executableTarget(
            name: "LumiApp",
            dependencies: ["LumiCore"],
            swiftSettings: [.swiftLanguageMode(.v5)],
        ),
    ]
    : []

let package = Package(
    name: "LumiIOS",
    platforms: [
        .iOS(.v16),
    ],
    targets: [
        .target(
            name: "LumiCore",
            swiftSettings: [.swiftLanguageMode(.v5)],
        ),
        .testTarget(
            name: "LumiCoreTests",
            dependencies: ["LumiCore"],
            swiftSettings: [.swiftLanguageMode(.v5)],
        ),
    ] + appTargets,
)
