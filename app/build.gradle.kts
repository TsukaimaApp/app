import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release key, local only (never committed): .local/keystore.properties with storeFile (relative to the repo root),
// storePassword, keyAlias, keyPassword. Without it (CI, fresh clones) the release APK is signed with the debug key.
val releaseKeystoreProperties: Properties? = rootProject.file(".local/keystore.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }

android {
    namespace = "app.tsukaima"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.tsukaima"
        minSdk = 29
        targetSdk = 36
        versionCode = 23
        versionName = "0.9.3"
        // libwebrtc (live voice) is 11.5 MB of native code per ABI; every Android 10+ phone is 64-bit ARM.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        releaseKeystoreProperties?.let { props ->
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (releaseKeystoreProperties != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn("Release signed with the debug keystore (.local/keystore.properties not found)")
                signingConfigs.getByName("debug")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Robolectric Compose UI tests (ui/*UiTest) read the merged resources and manifest.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)
    implementation(libs.okhttp)
    implementation(libs.jlatexmath.android)
    implementation(libs.stream.webrtc.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // The empty ComponentActivity that createComposeRule() launches (debug only; the release APK never has it).
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver3)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
}

// Reproduce a small CI runner locally: TSUKAIMA_TEST_CPUS=2 makes the test JVM report that many processors
// (Dispatchers.Default and every pool sized from availableProcessors() follow).
tasks.withType<Test>().configureEach {
    System.getenv("TSUKAIMA_TEST_CPUS")?.toIntOrNull()?.let { jvmArgs("-XX:ActiveProcessorCount=$it") }
}
