import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

// The release key lives outside the repository. Without keystore.properties the release build is signed with the
// debug key, so anyone building from source still gets an installable APK.
val releaseKey = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

android {
    namespace = "kr.tedin.maptalkdrive"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.tedin.maptalkdrive"
        // Pleos requires API 28 or higher for apps it distributes.
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // The ribbon server. The default is this device itself, which `adb reverse tcp:8787 tcp:8787` connects to the
        // server on the developer's machine; `-PribbonServerUrl=https://...` points a build elsewhere.
        val ribbonServerUrl = (project.findProperty("ribbonServerUrl") as String?) ?: "http://127.0.0.1:8787"
        buildConfigField("String", "RIBBON_SERVER_URL", "\"$ribbonServerUrl\"")
    }

    // CarPropertyManager (speed, gear) and CarUxRestrictionsManager (driving restrictions).
    useLibrary("android.car")

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":ribbon-core"))

    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    implementation("ai.pleos.playground:FusedLocation:1.1.3")
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.0")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
}

// Contest submission layout: apk/<team>_<app>_release.apk at the repository root.
tasks.register<Copy>("submissionApk") {
    dependsOn("assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(rootProject.layout.projectDirectory.dir("apk"))
    rename { "Tedin_MapRibbonForAgent_release.apk" }
}
