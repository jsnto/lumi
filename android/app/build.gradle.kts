plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Stable release signing, keyed by the environment (never by a checked-in file):
// the release keystore lives outside the repo and is mounted into the build by
// scripts/build-apk.sh, which exports LUMI_RELEASE_KEYSTORE (the keystore path),
// LUMI_RELEASE_KEYSTORE_PASSWORD, LUMI_RELEASE_KEY_ALIAS and LUMI_RELEASE_KEY_PASSWORD.
// With no key in the environment a release build is refused up front - a release
// APK is never signed ad hoc (that per-build debug identity is exactly what broke
// in-app updates and raised Play Protect false alarms).
val releaseSign = mapOf(
    "LUMI_RELEASE_KEYSTORE" to providers.environmentVariable("LUMI_RELEASE_KEYSTORE").orNull.orEmpty(),
    "LUMI_RELEASE_KEYSTORE_PASSWORD" to providers.environmentVariable("LUMI_RELEASE_KEYSTORE_PASSWORD").orNull.orEmpty(),
    "LUMI_RELEASE_KEY_ALIAS" to providers.environmentVariable("LUMI_RELEASE_KEY_ALIAS").orNull.orEmpty(),
    "LUMI_RELEASE_KEY_PASSWORD" to providers.environmentVariable("LUMI_RELEASE_KEY_PASSWORD").orNull.orEmpty(),
)
val hasReleaseSign = releaseSign.values.all { it.isNotEmpty() }

android {
    namespace = "com.lumi.player"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lumi.player"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.3.1"
    }

    // The stable release signing config must exist before the release build type
    // wires itself to it (the build type's block runs immediately).
    if (hasReleaseSign) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSign.getValue("LUMI_RELEASE_KEYSTORE"))
                storePassword = releaseSign.getValue("LUMI_RELEASE_KEYSTORE_PASSWORD")
                keyAlias = releaseSign.getValue("LUMI_RELEASE_KEY_ALIAS")
                keyPassword = releaseSign.getValue("LUMI_RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Stable release signing: the release build signs ONLY with the one
            // permanent release keystore, whose material reaches the build through
            // the environment (the releaseSign block above, set by the Dockerfile /
            // scripts/build-apk.sh which mount the keystore). Every published APK
            // therefore carries the same developer identity, so an in-app update
            // between releases passes the platform's signature check.
            if (hasReleaseSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Refuse a release build when the stable key is not in the environment (above):
// fail where the request is made, with the reason, instead of signing ad hoc.
// The check runs at configuration time over the requested task names (the Docker
// release build requests exactly assembleRelease); aggregate tasks (build,
// assemble) are a dev-only host path - an unsigned release APK cannot be
// installed anyway, and the Docker build's certificate verification is the
// backstop there.
if (!hasReleaseSign && gradle.startParameter.taskNames.any { it.substringAfterLast(':') == "assembleRelease" }) {
    throw GradleException(
        "assembleRelease is refused: no stable release signing key in the environment. " +
            "Release builds sign with the one permanent release keystore (kept outside the " +
            "repo - see README.md, 'Release signing'); scripts/build-apk.sh mounts it and " +
            "exports LUMI_RELEASE_KEYSTORE / LUMI_RELEASE_KEYSTORE_PASSWORD / " +
            "LUMI_RELEASE_KEY_ALIAS / LUMI_RELEASE_KEY_PASSWORD.",
    )
}

// Robolectric NATIVE-graphics Compose tests accumulate render/native state across
// test classes that share one executor JVM; that makes a later class's Compose
// idling (waitForIdle) never settle and fail with AppNotIdleException even though
// the same class passes in isolation. Fork a fresh executor JVM per test class so
// each class starts with clean Compose/native state.
tasks.withType<Test>().configureEach {
    forkEvery = 1
    // The Robolectric NATIVE-graphics workers live tight at the default
    // 512 m heap (Compose + Room + native rendering); give them a full
    // gigabyte so GC pressure can't stretch a main-thread convergence past
    // the tests' wall-clock poll budgets.
    maxHeapSize = "1g"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    // The settings "Update" area's release check + APK download (the
    // GitHub releases API fetch, the streamed download with progress,
    // and the latest-release JSON parse - see ui/UpdateManager.kt).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test.manifest)
}
