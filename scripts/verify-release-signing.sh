#!/usr/bin/env bash
# Verify that a lumi release APK is signed by the stable release keystore.
#
# The Docker release build (android/Dockerfile) runs this after assembleRelease;
# it can also be run on a host that has the Android build tools. It fails the
# build when the APK's signer certificate is not the keystore's certificate, so
# a release APK can never go out signed by the wrong key.
#
#   Usage: scripts/verify-release-signing.sh <apk> <keystore>
#
#   Requires: LUMI_RELEASE_KEYSTORE_PASSWORD (the keystore password) and,
#             optionally, LUMI_RELEASE_KEY_ALIAS (default "lumi-release").
#             keytool: on PATH (the JDK's). apksigner: on PATH, or under
#             ANDROID_BUILD_TOOLS_DIR (defaults to
#             /opt/android-sdk/build-tools/35.0.0 inside the Docker image).
set -euo pipefail

APK="${1:?usage: verify-release-signing.sh <apk> <keystore>}"
KEYSTORE="${2:?usage: verify-release-signing.sh <apk> <keystore>}"
ALIAS="${LUMI_RELEASE_KEY_ALIAS:-lumi-release}"
BUILD_TOOLS="${ANDROID_BUILD_TOOLS_DIR:-/opt/android-sdk/build-tools/35.0.0}"

if [ ! -f "$APK" ]; then
  echo "error: APK not found: $APK" >&2
  exit 1
fi
if [ ! -f "$KEYSTORE" ]; then
  echo "error: release keystore not found: $KEYSTORE" >&2
  exit 1
fi
if [ -z "${LUMI_RELEASE_KEYSTORE_PASSWORD:-}" ]; then
  echo "error: LUMI_RELEASE_KEYSTORE_PASSWORD is not set (the stable keystore's password)." >&2
  exit 1
fi

# Prefer apksigner on PATH; otherwise fall back to the build-tools directory.
APSIGNER="$(command -v apksigner || true)"
if [ -z "$APSIGNER" ]; then
  if [ -x "$BUILD_TOOLS/apksigner" ]; then
    APSIGNER="$BUILD_TOOLS/apksigner"
  else
    echo "error: apksigner is not on PATH or at $BUILD_TOOLS/apksigner." >&2
    exit 1
  fi
fi

# The APK's signer #1 certificate (apksigner prints lowercase, unseparated hex).
APK_SHA256="$("$APSIGNER" verify --print-certs "$APK" \
  | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1)" || true
if [ -z "$APK_SHA256" ]; then
  echo "error: could not read the APK's signer certificate (unsigned or malformed APK?): $APK" >&2
  exit 1
fi

# The keystore's certificate (keytool prints "AA:BB:..." uppercase; normalize
# to lowercase, unseparated hex to match apksigner's digest format).
KEYSTORE_SHA256="$(keytool -list -keystore "$KEYSTORE" -alias "$ALIAS" \
  -storepass "$LUMI_RELEASE_KEYSTORE_PASSWORD" 2>/dev/null \
  | sed -n 's/^.*Certificate fingerprint (SHA-256): //p' | head -n1 \
  | tr -d ' \t\r\n:' | tr 'A-F' 'a-f')" || true
if [ -z "$KEYSTORE_SHA256" ]; then
  echo "error: could not read the keystore's certificate (alias '$ALIAS'?). Check the keystore and password." >&2
  exit 1
fi

if [ "$APK_SHA256" != "$KEYSTORE_SHA256" ]; then
  echo "error: the APK is NOT signed by the stable release keystore." >&2
  echo "       APK signer SHA-256:    $APK_SHA256" >&2
  echo "       keystore SHA-256:     $KEYSTORE_SHA256" >&2
  echo "       The release must be refused - a wrong signature identity breaks in-app updates." >&2
  exit 1
fi

echo "    signing verified: APK carries the stable release keystore's certificate (SHA-256 $APK_SHA256)"
