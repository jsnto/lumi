# Builds the lumi debug APK with a pinned toolchain - no Android SDK or JDK
# needed on the host, only a Docker daemon.
#
#   FROM eclipse-temurin:17-jammy        -> OpenJDK 17 (AGP 8.7.3 requirement)
#   sdkmanager: platform-tools,
#     platforms;android-35,
#     build-tools;35.0.0                 -> exactly what the build needs
#   Gradle 8.13                          -> via the committed gradle wrapper

FROM eclipse-temurin:17-jammy

ENV ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/gradle/.gradle \
    PATH=/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

RUN apt-get update \
    && apt-get install -y --no-install-recommends wget unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Android command-line tools + the exact platform/build-tools versions.
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && wget -q https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O /tmp/clt.zip \
    && unzip -q /tmp/clt.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/clt.zip \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager --install "platform-tools" "platforms;android-35" "build-tools;35.0.0" >/dev/null

WORKDIR /src
# local.properties (host-specific sdk.dir) is excluded via .dockerignore;
# AGP resolves the SDK from ANDROID_HOME instead.
COPY . /src

# Entrypoint: run the Gradle build. The wrapper script (scripts/build-apk-docker.sh)
# starts the container, waits for the build, then copies the APK out.
CMD ["./gradlew", "assembleDebug", "--no-daemon", "--stacktrace"]
