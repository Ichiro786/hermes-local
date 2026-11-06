import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: CI writes keystore.properties (storeFile/storePassword/keyAlias/keyPassword) from
// repository secrets. Without it the release build falls back to the debug key (local builds only).
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -PengineAbi=x86_64 builds the CI emulator variant (x86_64 proot + x86_64 rootfs asset).
val engineAbi = (project.findProperty("engineAbi") as String?) ?: "arm64-v8a"

android {
    namespace = "dev.syed.hermeslocal"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.syed.hermeslocal"
        minSdk = 29
        targetSdk = 37
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0.0"
        ndk { abiFilters += engineAbi }
        buildConfigField("String", "ENGINE_ABI", "\"$engineAbi\"")
        // CI emulator builds only: intent extras drive setup (ci_password, ci_toggle) without UI automation.
        buildConfigField("boolean", "CI_HOOKS", "${(project.findProperty("ciHooks") as String?) == "true"}")
        buildConfigField("String", "ENGINE_REPO", "\"Ichiro786/hermes-local\"")
        buildConfigField("String", "ENGINE_TAG", "\"${(project.findProperty("engineTag") as String?) ?: "engine-v0.23.0-3"}\"")
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // proot and its loader are executables named lib*.so: extract them to nativeLibraryDir
        // (the only app path Android lets us exec since targetSdk 29) and keep them byte-for-byte.
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so", "**/libtalloc.so", "**/libandroid-shmem.so")
        }
    }

    androidResources {
        // The rootfs payload is streamed out of the APK: store it uncompressed.
        noCompress += listOf("xz")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric + Roborazzi screenshots of the main screen (./gradlew :app:testDebugUnitTest).
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("shots.dir", System.getenv("SHOTS_DIR") ?: "build/shots")
            // Robolectric reaches into JDK internals (FileDescriptor etc.) on newer JDKs.
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED", "--add-opens=java.base/java.lang=ALL-UNNAMED", "--add-opens=java.base/java.nio=ALL-UNNAMED")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
        )
    }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Tar reader + pure-Java xz decoder for the rootfs payload (same as OpenBot Local).
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
