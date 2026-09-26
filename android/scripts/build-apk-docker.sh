#!/usr/bin/env bash
# Build the lumi debug APK inside Docker - no Android SDK or JDK on the host.
#
#   Usage:     ./android/scripts/build-apk-docker.sh   (from the repo root)
#   Output:    dist/lumi-app-debug.apk
#   Overrides: LUMI_DOCKER_IMAGE=<image>   (default lumi-apk-builder:1)
#              OUT_DIR=<dir>               (default dist)
#
# The first run downloads the Android SDK (~500 MB) into the image and the
# Gradle distribution via the wrapper; subsequent runs reuse the named
# gradle-cache volume for a fast incremental build.
set -euo pipefail
# The script lives in <repo root>/android/scripts/; build from the repo root
# (the Docker context), with the Dockerfile referenced under android/.
cd "$(dirname "$0")/../.."

IMAGE="${LUMI_DOCKER_IMAGE:-lumi-apk-builder:1}"
VOLUME="${LUMI_GRADLE_VOLUME:-lumi-gradle-cache}"
OUT_DIR="${OUT_DIR:-dist}"
APK_NAME="lumi-app-debug.apk"

if ! docker info >/dev/null 2>&1; then
  echo "error: cannot reach the Docker daemon (docker info failed)." >&2
  echo "       Start Docker, or on Linux add your user to the 'docker' group." >&2
  exit 1
fi

mkdir -p "$OUT_DIR"
docker volume create "$VOLUME" >/dev/null

echo ">> Building Docker image $IMAGE (toolchain: JDK 17, Android SDK 35, build-tools 35.0.0)..."
docker build -t "$IMAGE" -f android/Dockerfile .

cid="$(docker create -v "$VOLUME":/home/gradle/.gradle "$IMAGE")"
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true' EXIT

echo ">> Running gradle assembleDebug in the container..."
docker start -a "$cid" || true
code="$(docker inspect -f '{{.State.ExitCode}}' "$cid")"
if [ "$code" != "0" ]; then
  echo ">> Gradle build FAILED (exit code $code) - see the build log above." >&2
  exit "$code"
fi

docker cp "$cid:/src/android/app/build/outputs/apk/debug/app-debug.apk" "$OUT_DIR/$APK_NAME"
echo ">> Done. APK: $(pwd)/$OUT_DIR/$APK_NAME"
