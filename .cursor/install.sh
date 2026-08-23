#!/usr/bin/env bash
#
# Cloud Agent install script for the Evidessa / VitalSignal research foundation.
#
# Prepares the two development lanes the repository documents:
#   1. Dependency-free preflight checks (Python validator + Node prototype tests).
#   2. The Android/Gradle simulator build (JVM unit tests + phone/wear debug APKs).
#
# The default Cloud Agent image already provides JDK 21, Node and Python, so the
# only missing prerequisite is the Android SDK, which this script installs into a
# stable, snapshot-friendly location. It is idempotent: on a machine that already
# has the SDK (for example when booting from a prebuilt environment snapshot) the
# download and package installs are skipped.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Pinned for deterministic setup. Matches the API 37 platform / build-tools the
# project's Gradle modules target (compileSdk = 37).
CMDLINE_TOOLS_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
SDK_PACKAGES=("platform-tools" "platforms;android-37.0" "build-tools;37.0.0")

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> Preparing Android SDK at $ANDROID_HOME"
if [ ! -x "$SDKMANAGER" ]; then
  echo "    Installing Android command-line tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp_zip="$(mktemp --suffix=.zip)"
  curl -fsSL -o "$tmp_zip" "$CMDLINE_TOOLS_URL"
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/.unpack"
  mkdir -p "$ANDROID_HOME/cmdline-tools/.unpack"
  unzip -q "$tmp_zip" -d "$ANDROID_HOME/cmdline-tools/.unpack"
  mv "$ANDROID_HOME/cmdline-tools/.unpack/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$ANDROID_HOME/cmdline-tools/.unpack" "$tmp_zip"
else
  echo "    Command-line tools already present"
fi

echo "==> Accepting SDK licenses and installing packages"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
yes | "$SDKMANAGER" "${SDK_PACKAGES[@]}" >/dev/null 2>&1 || true

echo "==> Pointing Gradle at the SDK (local.properties)"
"$REPO_ROOT/.cursor/configure-gradle-sdk.sh"

# Warm the Gradle wrapper, Android Gradle Plugin and dependency caches so the
# first interactive build is fast. Best-effort: transient network hiccups while
# resolving dependencies must not fail environment setup. Lint is intentionally
# excluded here because it aborts on a pre-existing source-level warning; run
# `./gradlew lint` directly when you need the lint report.
echo "==> Warming Gradle build caches (best-effort)"
if ./gradlew --no-daemon :phone:assembleDebug :wear:assembleDebug test -x lint >/tmp/gradle-warm.log 2>&1; then
  echo "    Gradle warm build succeeded (unit tests passed, debug APKs assembled)"
else
  echo "    Gradle warm build did not complete; see /tmp/gradle-warm.log (setup continues)"
fi

echo "==> Install complete. ANDROID_HOME=$ANDROID_HOME"
