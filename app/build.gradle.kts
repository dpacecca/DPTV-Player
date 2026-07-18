plugins {
    alias(libs.plugins.android.application)
    // Kotlin is provided by AGP 9's built-in Kotlin support. KSP 2.3.6+ is compatible with it.
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tv.own.owntv"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "tv.own.owntv"
        minSdk = 26
        targetSdk = 36
        // CI injects these from the git tag (see .github/workflows/android.yml) so releases never
        // need a manual edit here. The fallbacks are only used for local/debug builds — pinned HIGH
        // (99999, mirroring versionName 99.99.99) so a local/debug APK is always "newer" than any
        // published release and installs straight over it (no INSTALL_FAILED_VERSION_DOWNGRADE).
        versionCode = (System.getenv("VERSION_CODE") ?: "99999").toInt()
        // CI injects VERSION_NAME from the git tag for releases. The fallback is only ever used by
        // LOCAL builds (i.e. debug), so we pin it to 99.99.99 — that way a dev build is always "newer"
        // than any published release and the in-app updater never offers an "update" while developing.
        versionName = System.getenv("VERSION_NAME") ?: "99.99.99"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ABI split via product flavors: real Android TV / Fire TV hardware is arm (arm64-v8a covers
    // everything modern; armeabi-v7a keeps the original 32-bit Nvidia Shield TV 2015/2017, which runs
    // Android 9+ but is 32-bit). x86_64 is emulator-only — no real TV box uses it. Shipping them as
    // separate flavors halves the download users get via the Downloader code (~49MB vs the old 104MB
    // universal APK that bundled all 4 ABIs), which fixes the "parse error on install" reports caused
    // by truncated downloads. x86 (32-bit Intel) is dropped entirely — even emulators use x86_64.
    //
    // Local dev: pick a flavor in Android Studio's "Build Variants" panel before Run (standard for
    // real devices / arm emulators, x86_64 for an x86_64 emulator). `assembleRelease` builds BOTH.
    flavorDimensions += "abi"
    productFlavors {
        create("standard") {
            dimension = "abi"
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
        create("x86_64") {
            dimension = "abi"
            ndk { abiFilters += listOf("x86_64") }
        }
    }

    // Release signing is driven by env vars (set from GitHub secrets in CI). When they're absent
    // — local dev, debug builds, or unsigned CI — nothing here applies, so builds still work.
    val releaseKeystore = System.getenv("KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    testOptions {
        // JVM unit tests hit android.util.Log / SystemClock in the code under test (StalkerAuthManager
        // etc.); return defaults (no-op log, 0 clock) instead of "not mocked" crashes.
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            optimization {
                enable = true
                keepRules {
                    files("proguard-rules.pro")
                }
            }
            if (releaseKeystore != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["androidTest"].assets.directories.add("$projectDir/schemas")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Compose (BOM-managed)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)

    // Compose for TV
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)

    // Lifecycle / Navigation
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    // Preferences
    implementation(libs.androidx.datastore.preferences)

    // WorkManager (durable background sync)
    implementation(libs.androidx.work.runtime)

    // Baseline profiles: installs the merged library profiles (Compose, Media3, Room, ...) into
    // ART on first launch. OwnTV is sideloaded, so without this the bundled profiles never apply.
    implementation(libs.androidx.profileinstaller)

    // Database (Room, via KSP) + Paging
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.zxing.core) // QR generation for the Remote (companion) add-source flow
    implementation(libs.juniversalchardet) // local subtitle charset detection (subtitle plan §7.2)

    // Media playback — libmpv (FFmpeg) engine
    implementation(libs.libmpv)
    // Media3 / ExoPlayer — used ONLY for the VOD + image-subtitle (PGS/VOBSUB/DVB) handoff, where it
    // keeps video zero-copy AND renders bitmap subs on its own layer (mpv's direct path can't). Not a
    // sidecar: mpv is stopped first, so the provider only ever sees one connection.
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls) // HLS (.m3u8) support for the Live preview engine
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.datasource.okhttp)

    // In-app YouTube trailer playback (plan §7.3) — WebView-backed IFrame player; the only ToS-clean
    // way to play YouTube trailers inside the app. Falls back to an "Open in YouTube" intent.
    implementation(libs.youtube.player)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Dependency injection
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)


    // Debug tooling
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
