# Android build image for local-tts-reader.
#
# Bakes in JDK 21 + Android command-line tools + SDK platform/build-tools + NDK, so the
# SDK's tens of thousands of files never land in the workspace. Gradle/Maven caches
# live in Docker named volumes (see tools/docker-build.sh); the project directory is
# mounted read/write at build time and stays source-only.
#
#   docker build -t localtts-android .
#   tools/docker-build.sh assembleDebug
#
# Version pins: platform/build-tools match the app's compileSdk; the NDK is needed by
# tess-two (core-ocr, Tesseract native). Bump them when the toolchain moves.

FROM ubuntu:24.04

ENV DEBIAN_FRONTEND=noninteractive
ENV ANDROID_HOME=/opt/android-sdk
ENV ANDROID_SDK_ROOT=/opt/android-sdk
ENV PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

# JDK 21 (project targets JVM 17 bytecode; Gradle runs on the newer JDK)
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-21-jdk-headless \
        unzip curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Command-line tools (small bootstrap; the SDK itself is installed below)
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline-tools.zip \
       https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/cmdline-tools.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline-tools.zip

# SDK packages: platform-tools (adb), one platform, matching build-tools, and the NDK
RUN yes | sdkmanager --licenses > /dev/null \
    && sdkmanager \
        "platform-tools" \
        "platforms;android-36" \
        "build-tools;36.0.0" \
        "ndk;27.2.12479018"

RUN mkdir -p /workspace
WORKDIR /workspace
