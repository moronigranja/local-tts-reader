#!/usr/bin/env bash
# Run a Gradle task inside the Android toolchain image.
#
# The SDK/NDK are baked into the image; Gradle/Maven caches live in named volumes, so
# rebuilds are fast and the workspace stays source-only. Built APKs land in the
# workspace (root-owned — fine for adb install).
#
# Usage:
#   tools/docker-build.sh :core-locate:test     # JVM-only, fast
#   tools/docker-build.sh assembleDebug         # full APK
#   tools/docker-build.sh installDebug          # build + adb install to connected device
set -euo pipefail

IMAGE="${IMAGE:-localtts-android}"

if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
  echo "Image '$IMAGE' not found. Build it first:  docker build -t $IMAGE ." >&2
  exit 1
fi

exec docker run --rm \
  -v "$PWD":/workspace \
  -v android-gradle:/root/.gradle \
  -v android-local:/root/.local \
  "$IMAGE" ./gradlew "$@"
