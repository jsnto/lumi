# syntax = docker/dockerfile:1
#
# The lumi iOS app's IPA builder.
#
# Builds the arm64-apple-ios executable with the open-source Swift
# toolchain (no Xcode, no macOS on the build host) and assembles a
# structurally valid .ipa (Payload/Lumi.app). The result is UNSIGNED:
# iOS installs require a signature, so a sideload tool (AltStore,
# Sideloadly, the like) re-signs the app with the captain's own Apple ID
# at install time (see ios/README.md).
#
# INPUT (required, mounted at container run time): the iOS Swift SDK
# directory (a swift-sdk-generator output: swiftSDK.json + the iOS
# platform files) at /ios-sdk. The build host's script mounts it
# (docker create -v). The entrypoint fails loudly with instructions when
# it is missing: the SDK is the one thing this image cannot fetch or
# generate itself (the platform files are Apple's).
#
# OUTPUT: /out/lumi-ios.ipa (copied out with docker cp by the script).
#
# The build-time steps below only sanity-check the package on the Linux
# base (the core layer builds and the manifest compiles); the iOS build
# itself is the entrypoint, which sees the mounted SDK.

ARG SWIFT_VERSION=6.4.0
FROM swift:${SWIFT_VERSION}-jammy

WORKDIR /src
COPY ios/ ./ios/

# Build-time sanity check: the manifest compiles and the pure core layer
# builds on the Linux base (a broken package fails here, before the SDK
# build). The iOS-only app target is the entrypoint's job: the manifest
# gates it on LUMI_BUILD_APP, so the sanity build runs WITHOUT the var
# (the app target is excluded) and the entrypoint's own build sets it.
RUN swift build --package-path ios -c release

# The entrypoint (ios/scripts/build-ipa-docker.sh runs the container and
# copies /out/lumi-ios.ipa out): verify the mounted SDK, build the arm64
# release for the iOS platform WITH the app target (LUMI_BUILD_APP=1 -
# exported here, in the entrypoint's shell, so the build-time sanity step
# above never sees it), and assemble the .app + the .ipa
# (Payload/Lumi.app with the binary + Info.plist - the structurally
# valid shape the App Store / sideload tools expect).
CMD ["bash", "-c", "set -eux; \
    test -d /ios-sdk; \
    test -f /ios-sdk/swiftSDK.json; \
    export LUMI_BUILD_APP=1; \
    # The bin path is QUERIED (swift build --show-bin-path with the same
    # build flags) rather than guessed: the package-path build lives under
    # ios/.build and the cross-compile output sits in the triple-suffixed
    # products directory (SwiftPM's out/Products layout), so a hardcoded
    # .build/release would miss. \
    BIN_PATH=\"$(swift build --show-bin-path --package-path ios -c release --arch arm64 --swift-sdk /ios-sdk)\"; \
    swift build --package-path ios -c release --arch arm64 --swift-sdk /ios-sdk; \
    mkdir -p /out/Payload/Lumi.app; \
    install -m 0755 \"$BIN_PATH/LumiApp\" /out/Payload/Lumi.app/Lumi; \
    install -m 0644 ios/packaging/Info.plist /out/Payload/Lumi.app/Info.plist; \
    cd /out; \
    python3 -m zipfile -c lumi-ios.ipa Payload; \
    python3 -m zipfile -l /out/lumi-ios.ipa"]
