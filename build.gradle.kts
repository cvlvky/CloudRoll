// Same toolchain as TMPlayer's working build: AGP 9 compiles Kotlin itself, and the Kotlin Gradle
// plugin version comes from the plugins declared here.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
