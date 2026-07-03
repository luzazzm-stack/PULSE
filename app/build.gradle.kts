plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.pulse"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.pulse"
        minSdk = 21
        targetSdk = 33
        versionCode = 25
        versionName = "0.9.7"
        base.archivesName.set("Emma-v$versionName")
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/pulse-debug.jks")
            storePassword = "pulsekey"
            keyAlias = "pulse"
            keyPassword = "pulsekey"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            if (rootProject.file("keystore/pulse-debug.jks").exists()) {
                signingConfig = signingConfigs.getByName("shared")
            }
        }
    }
    // Two side-by-side builds, one per candidate logo (different applicationId so both install at once).
    flavorDimensions += "logo"
    productFlavors {
        create("nege") { dimension = "logo"; applicationIdSuffix = ".nege" }   // #06 Negative-space E
        create("beat") { dimension = "logo"; applicationIdSuffix = ".beat" }   // #12 Beat mark
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = freeCompilerArgs + listOf("-Xjvm-default=all")
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.02")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material-ripple")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Media3 playback
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-session:1.2.1")
    implementation("androidx.media3:media3-common:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")

    // Ad-free YouTube engine (NewPipe extractor) + HTTP
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Updates the device TLS/security provider on old phones (e.g. Galaxy J7) so HTTPS to YouTube works
    implementation("com.google.android.gms:play-services-base:18.5.0")

    // Java 8+ APIs (java.time etc. used by NewPipeExtractor) on Android 5+
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // Album art / thumbnails
    implementation("io.coil-kt:coil-compose:2.5.0")
}
