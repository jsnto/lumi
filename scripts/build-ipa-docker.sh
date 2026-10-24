#!/usr/bin/env bash
# Build the lumi iOS app's .ipa inside Docker - no Xcode, no macOS, no
# Swift toolchain on the host (only a Docker daemon + the iOS Swift SDK).
#
#   Usage:     ./scripts/build-ipa-docker.sh   (from the repo root)
#   Output:    dist/lumi-ios.ipa
#   Overrides: LUMI_DOCKER_IMAGE=<image>   (default lumi-ipa-builder:1)
#              LUMI_IOS_SDK_DIR=<dir>      (default ios/sdk - the iOS
#                                           Swift SDK directory; see the
#                                           ios/ README for how to make
#                                           one)
#
# The image builds the arm64-apple-ios executable with the open-source
# Swift toolchain and assembles the structurally valid (unsigned) .ipa;
# a sideload tool re-signs it at install time. The build fails loudly
# when the iOS Swift SDK is missing - it is the one input this image
# cannot fetch or generate (the iOS platform files are Apple's).
set -euo pipefail
# The script lives in <repo root>/scripts/; build from the repo root
# (the Docker context), with the Dockerfile referenced under ios/.
cd "$(dirname "$0")/.."

IMAGE="${LUMI_DOCKER_IMAGE:-lumi-ipa-builder:1}"
SDK_DIR="${LUMI_IOS_SDK_DIR:-ios/sdk}"
OUT_DIR="${OUT_DIR:-dist}"
IPA_NAME="lumi-ios.ipa"

if ! docker info >/dev/null 2>&1; then
  echo "error: cannot reach the Docker daemon (docker info failed)." >&2
  echo "       Start Docker, or on Linux add your user to the 'docker' group." >&2
  exit 1
fi

if [ ! -d "$SDK_DIR" ]; then
  echo "error: the iOS Swift SDK directory is missing: $SDK_DIR" >&2
  echo "" >&2
  echo "The SDK is a swift-sdk-generator output (swiftSDK.json + the iOS" >&2
  echo "platform files). It cannot be fetched or generated inside Docker" >&2
  echo "(the platform files are Apple's). How to produce one on a Mac:" >&2
  echo "" >&2
  echo "  1. On a Mac with Xcode + the Swift 6 toolchain, run" >&2
  echo "     swift-sdk-generator against the iphoneos platform files" >&2
  echo "     (xcrun --sdk iphoneos --show-sdk-path)." >&2
  echo "  2. Put the generated SDK directory at ios/sdk (or point" >&2
  echo "     LUMI_IOS_SDK_DIR at it)." >&2
  echo "" >&2
  echo "See ios/README.md ('Building the IPA') for the full walkthrough." >&2
  exit 1
fi
if [ ! -f "$SDK_DIR/swiftSDK.json" ]; then
  echo "error: $SDK_DIR is not a Swift SDK directory (no swiftSDK.json)." >&2
  echo "       Point LUMI_IOS_SDK_DIR at a swift-sdk-generator output." >&2
  exit 1
fi

# Make the SDK path absolute (docker mounts need host paths).
SDK_DIR="$(cd "$SDK_DIR" && pwd)"

mkdir -p "$OUT_DIR"

echo ">> Building Docker image $IMAGE (toolchain: Swift 6.4.0 on jammy)..."
docker build -t "$IMAGE" -f ios/Dockerfile .

cid="$(docker create -v "$SDK_DIR":/ios-sdk:ro "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo ">> Running the arm64 iOS release build in the container..."
docker start -a "$cid" || true
code="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
if [ "$code" != "0" ]; then
  echo ">> iOS build FAILED (exit code $code) - see the build log above." >&2
  exit "$code"
fi

docker cp "$cid:/out/$IPA_NAME" "$OUT_DIR/$IPA_NAME"
docker rm -f "$cid" >/dev/null 2>&1 || true
trap - EXIT

echo ">> Verifying the .ipa structure..."
python3 - "$OUT_DIR/$IPA_NAME" <<'PY'
import sys, zipfile, plistlib

path = sys.argv[1]
zf = zipfile.ZipFile(path)
names = zf.namelist()
required = ["Payload/Lumi.app/Lumi", "Payload/Lumi.app/Info.plist"]
missing = [n for n in required if n not in names]
if missing:
    sys.exit("error: the .ipa is missing entries: " + ", ".join(missing))

binary = zf.read("Payload/Lumi.app/Lumi")
# Mach-O arm64 magic (0xfeedfacf, little-endian on disk: cffaedfe) or a
# fat/universal header (0xcafebabe / 0xbebafeca).
magic = binary[:4]
ok_magic = magic in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf", b"\xca\xfe\xba\xbe", b"\xbe\xba\xfe\xca")
if not ok_magic:
    sys.exit("error: the bundled binary is not a Mach-O image: " + repr(magic))
if magic in (b"\xcf\xfa\xed\xfe", b"\xfe\xed\xfa\xcf"):
    cpu_type = int.from_bytes(binary[4:8], "little" if magic[:1] == b"\xcf" else "big")
    if cpu_type != 0x01000007:  # CPU_TYPE_ARM64
        sys.exit("error: the bundled binary is not arm64 (cpu type 0x%x)" % cpu_type)

plist = plistlib.loads(zf.read("Payload/Lumi.app/Info.plist"))
for key in ("CFBundleIdentifier", "CFBundleExecutable", "CFBundleName"):
    if key not in plist:
        sys.exit("error: Info.plist is missing " + key)
modes = plist.get("UIBackgroundModes", [])
if "audio" not in modes:
    sys.exit("error: Info.plist does not declare the background-audio mode")
if "NSNFCReaderUsageDescription" not in plist:
    sys.exit("error: Info.plist does not declare the NFC usage description")
caps = plist.get("UIRequiredDeviceCapabilities", [])
if "nfc" not in caps:
    sys.exit("error: Info.plist does not require the nfc capability")
# The launcher icons: every CFBundleIconFiles name must be a PNG in the
# bundle (the pre-asset-catalog surface reads them straight from the top
# level of the app).
icons = plist.get("CFBundleIconFiles", [])
if not icons:
    sys.exit("error: Info.plist does not declare CFBundleIconFiles")
missing_icons = [i for i in icons if "Payload/Lumi.app/" + i + ".png" not in names]
if missing_icons:
    sys.exit("error: the app bundle is missing the declared icons: " + ", ".join(missing_icons))

print("    binary: Mach-O arm64 OK")
print("    Info.plist: bundle %s, background audio, NFC declared" % plist["CFBundleIdentifier"])
print("    icons: %d declared, all present" % len(icons))
print("    entries: %d" % len(names))
PY

echo ">> Done. IPA: $(pwd)/$OUT_DIR/$IPA_NAME (unsigned - see the ios/ README for installing it)."
