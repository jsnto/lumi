# Builds the signed lumi release APK with a pinned toolchain - no Android SDK or
# JDK needed on the host, only a Docker daemon (plus the stable release keystore,
# mounted at /keys by scripts/build-apk.sh; see README.md, 'Release signing').
#
#   FROM eclipse-temurin:17-jammy        -> OpenJDK 17 (AGP 8.7.3 requirement)
#   sdkmanager: platform-tools,
#     platforms;android-35,
#     build-tools;35.0.0                 -> exactly what the build needs
#   Gradle 8.13                          -> via the committed gradle wrapper

FROM eclipse-temurin:17-jammy

# JDK: temurin installs the Java runtime at /opt/java/openjdk and sets
# JAVA_HOME, but its PATH entry for the JDK bin dir is replaced by the ENV
# below - list it explicitly so every tool in the container (gradle, keytool,
# apksigner) finds java on PATH.
ENV JAVA_HOME=/opt/java/openjdk \
    ANDROID_HOME=/opt/android-sdk \
    GRADLE_USER_HOME=/home/gradle/.gradle \
    PATH=/opt/java/openjdk/bin:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

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
# The build context is the repo root (scripts/build-apk.sh builds
# it with -f android/Dockerfile); the Android project lives under android/.
# local.properties (host-specific sdk.dir) is excluded via the repo-root
# .dockerignore; AGP resolves the SDK from ANDROID_HOME instead.
COPY . /src

# Entrypoint: run the Gradle release build, then verify the APK carries the
# stable release keystore's certificate (the key material is mounted at /keys
# and LUMI_RELEASE_KEYSTORE / LUMI_RELEASE_KEYSTORE_PASSWORD / LUMI_RELEASE_KEY_ALIAS
# / LUMI_RELEASE_KEY_PASSWORD are set by scripts/build-apk.sh).
CMD ["bash", "-c", "cd android && ./gradlew assembleRelease --no-daemon --stacktrace && /src/scripts/verify-release-signing.sh /src/android/app/build/outputs/apk/release/app-release.apk \"$LUMI_RELEASE_KEYSTORE\""]
