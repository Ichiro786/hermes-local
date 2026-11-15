plugins {
    id("com.android.application") version "9.5.1" apply false
    // AGP 9 compiles Kotlin itself; the Compose compiler plugin pins the Kotlin version (2.4.x, which
    // the current androidx artifacts are built with).
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
