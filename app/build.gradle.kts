import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The build number from GitHub Actions (github.run_number). A local build is 1.
val buildNumber: Int = (System.getenv("CLOUDROLL_VERSION_CODE") ?: "1").toIntOrNull() ?: 1
val keystorePath: String? = System.getenv("CLOUDROLL_KEYSTORE")
val keystorePassword: String = System.getenv("CLOUDROLL_KEYSTORE_PASSWORD") ?: ""

android {
    namespace = "com.cvlvky.cloudroll"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.cvlvky.cloudroll"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        buildConfigField("String", "REPO", "\"cvlvky/CloudRoll\"")
        // Optional movie keys from repository secrets; the app also takes them in Settings.
        buildConfigField("String", "TMDB_KEY", "\"${System.getenv("TMDB_API_KEY") ?: ""}\"")
        buildConfigField("String", "OMDB_KEY", "\"${System.getenv("OMDB_API_KEY") ?: ""}\"")
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        if (keystorePath != null && file(keystorePath).exists()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "cloudroll"
                keyPassword = keystorePassword
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf("META-INF/*.version", "DebugProbesKt.bin", "META-INF/LICENSE*", "META-INF/NOTICE*")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    implementation("dev.g000sha256:tdl-coroutines:15.0.0")
    implementation("com.google.zxing:core:3.5.4")

    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-session:1.11.1")

    // Images: Coil loads and caches pictures (Telegram files and movie posters); Telephoto zooms
    // big photos with sub-sampling, so a 50 MP original stays sharp without filling the memory.
    implementation("io.coil-kt.coil3:coil-compose:3.5.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.5.0")
    implementation("io.coil-kt.coil3:coil-gif:3.5.0")
    implementation("me.saket.telephoto:zoomable-image-coil3:0.16.0")
}
