#!/usr/bin/env bash
# Build the signed release APK for lumi inside Docker - no Android SDK or JDK on
# the host, only a Docker daemon plus the stable release keystore.
#
#   Usage:     ./scripts/build-apk.sh   (from the repo root)
#   Output:    android/dist/lumi-app-release.apk
#   Overrides: LUMI_DOCKER_IMAGE=<image>   (default lumi-apk-builder:1)
#              OUT_DIR=<dir>               (default android/dist)
#              LUMI_KEYSTORE_DIR=<dir>     (default /home/jasonto/dev/firstmate/keystores/lumi -
#                                           the stable release keystore; see README.md,
#                                           'Release signing')
#
# Every release APK is signed with the one permanent release keystore: the
# keystore directory is mounted read-only at /keys, the signing environment is
# exported into the container, and the build verifies the produced APK carries
# the keystore's certificate (scripts/verify-release-signing.sh) before it is
# reported done. The key material lives outside the repo and is never committed.
#
# The first run downloads the Android SDK (~500 MB) into the image and the
# Gradle distribution via the wrapper; subsequent runs reuse the named
# gradle-cache volume for a fast incremental build.
set -euo pipefail
# The script lives in <repo root>/scripts/; build from the repo root
# (the Docker context), with the Dockerfile referenced under android/.
cd "$(dirname "$0")/.."

IMAGE="${LUMI_DOCKER_IMAGE:-lumi-apk-builder:1}"
VOLUME="${LUMI_GRADLE_VOLUME:-lumi-gradle-cache}"
OUT_DIR="${OUT_DIR:-android/dist}"
APK_NAME="lumi-app-release.apk"
KEYSTORE_DIR="${LUMI_KEYSTORE_DIR:-/home/jasonto/dev/firstmate/keystores/lumi}"
KEYSTORE_NAME="lumi-release.p12"
PW_FILE_NAME="lumi-release.p12.pw"
KEY_ALIAS="lumi-release"

if ! docker info >/dev/null 2>&1; then
  echo "error: cannot reach the Docker daemon (docker info failed)." >&2
  echo "       Start Docker, or on Linux add your user to the 'docker' group." >&2
  exit 1
fi

if [ ! -f "$KEYSTORE_DIR/$KEYSTORE_NAME" ] || [ ! -f "$KEYSTORE_DIR/$PW_FILE_NAME" ]; then
  echo "error: the stable release keystore is missing from $KEYSTORE_DIR" >&2
  echo "       (expected $KEYSTORE_NAME + $PW_FILE_NAME)." >&2
  echo "       It is kept outside the repo on the release host - see README.md," >&2
  echo "       'Release signing'. Point LUMI_KEYSTORE_DIR at it." >&2
  exit 1
fi
# Make the keystore path absolute (docker mounts need host paths).
KEYSTORE_DIR="$(cd "$KEYSTORE_DIR" && pwd)"
KEYSTORE_PASSWORD="$(cat "$KEYSTORE_DIR/$PW_FILE_NAME")"

mkdir -p "$OUT_DIR"
docker volume create "$VOLUME" >/dev/null

echo ">> Building Docker image $IMAGE (toolchain: JDK 17, Android SDK 35, build-tools 35.0.0)..."
docker build -t "$IMAGE" -f android/Dockerfile .

cid="$(docker create \
  -v "$VOLUME":/home/gradle/.gradle \
  -v "$KEYSTORE_DIR":/keys:ro \
  -e "LUMI_RELEASE_KEYSTORE=/keys/$KEYSTORE_NAME" \
  -e "LUMI_RELEASE_KEYSTORE_PASSWORD=$KEYSTORE_PASSWORD" \
  -e "LUMI_RELEASE_KEY_ALIAS=$KEY_ALIAS" \
  -e "LUMI_RELEASE_KEY_PASSWORD=$KEYSTORE_PASSWORD" \
  "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo ">> Running the signed release build in the container (keystore: $KEYSTORE_DIR)..."
docker start -a "$cid" || true
code="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
if [ "$code" != "0" ]; then
  echo ">> Release build FAILED (exit code $code) - see the build log above." >&2
  exit "$code"
fi

docker cp "$cid:/src/android/app/build/outputs/apk/release/app-release.apk" "$OUT_DIR/$APK_NAME"
echo ">> Done. APK: $(pwd)/$OUT_DIR/$APK_NAME (signed with the stable release key - certificate verified in the build log above)."
