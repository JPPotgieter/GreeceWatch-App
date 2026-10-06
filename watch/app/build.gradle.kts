import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Relay settings live in watch/local.properties (not committed). See README.
val local = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun localString(key: String, default: String = "") =
    "\"" + (local.getProperty(key) ?: default).replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.greecewatch.mob"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.greecewatch.mob"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "RELAY_URL", localString("relay.url"))
        buildConfigField("String", "RELAY_TOKEN", localString("relay.token"))
        buildConfigField("String", "CREW_NAME", localString("crew.name", "Crew member"))
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded onto your own watch, so the debug key is fine.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
