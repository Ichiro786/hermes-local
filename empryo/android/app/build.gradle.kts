import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: CI writes keystore.properties from the Hermes Local repository secrets.
// Without it the release build falls back to the debug key.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// -PengineAbi=x86_64 builds the CI emulator variant (x86_64 proot + rootfs, linux-x64-baseline Empryo).
val engineAbi = (project.findProperty("engineAbi") as String?) ?: "arm64-v8a"
val empryoVersion = "3.12.8-beta"
// Pinned from https://dl.empryo.com/v3.12.8-beta/SHA256SUMS.txt (checked again by the app at download).
val empryoAsset = if (engineAbi == "x86_64") "empryo-$empryoVersion-linux-x64-baseline.tar.gz" else "empryo-$empryoVersion-linux-arm64.tar.gz"
val empryoSha = if (engineAbi == "x86_64") "6958364896c00f88ade81470432487fc0942339c7d9102279d45a700424faf00"
    else "db9054f4f8ad0325a50ddc921204ea7cf1556f843adabb90c60474b2de40df91"

android {
    namespace = "run.hermes.empryo"
    compileSdk = 37

    defaultConfig {
        // Working name; a distinct id so it installs beside Hermes Local.
        applicationId = "run.hermes.empryo.test"
        minSdk = 29
        targetSdk = 37
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0-test1"
        ndk { abiFilters += engineAbi }
        buildConfigField("String", "ENGINE_ABI", "\"$engineAbi\"")
        buildConfigField("boolean", "CI_HOOKS", "${(project.findProperty("ciHooks") as String?) == "true"}")
        buildConfigField("String", "EMPRYO_VERSION", "\"$empryoVersion\"")
        buildConfigField("String", "EMPRYO_URL", "\"https://dl.empryo.com/v$empryoVersion/$empryoAsset\"")
        buildConfigField("String", "EMPRYO_SUMS_URL", "\"https://dl.empryo.com/v$empryoVersion/SHA256SUMS.txt\"")
        buildConfigField("String", "EMPRYO_ASSET", "\"$empryoAsset\"")
        buildConfigField("String", "EMPRYO_SHA256", "\"$empryoSha\"")
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
            // Test build: release-like (no ui-tooling) but debuggable so `run-as` works on the phone.
            isDebuggable = true
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
        // proot + loader are executables named lib*.so: extracted to nativeLibraryDir (the only exec-able path).
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot-loader.so", "**/libtalloc.so", "**/libandroid-shmem.so")
        }
    }

    androidResources {
        noCompress += listOf("xz")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("roborazzi.test.record", "true")
            it.systemProperty("shots.dir", System.getenv("SHOTS_DIR") ?: "build/shots")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.apache.commons:commons-compress:1.27.1")
    implementation("org.tukaani:xz:1.10")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.77.0")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.77.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
