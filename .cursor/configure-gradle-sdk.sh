#!/usr/bin/env bash
#
# Point Gradle at the Android SDK for this checkout.
#
# Runs on every Cloud Agent boot (via the environment `start` command) as well
# as from install.sh. `local.properties` is gitignored and per-checkout, and the
# `install` step is not re-run when an agent boots from a prebuilt snapshot, so
# this lightweight, idempotent step guarantees Gradle can locate the SDK in a
# freshly checked-out working tree.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"

if [ -d "$ANDROID_HOME/platforms" ]; then
  printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$REPO_ROOT/local.properties"
  echo "Configured Gradle SDK: $REPO_ROOT/local.properties -> sdk.dir=$ANDROID_HOME"
else
  echo "WARNING: Android SDK not found at $ANDROID_HOME; run .cursor/install.sh first" >&2
fi
