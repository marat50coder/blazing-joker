import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

val keystoreProps = Properties().also { props ->
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) props.load(propsFile.inputStream())
}

android {
    namespace = "com.blazingjoker.blazingjokergame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.blazingjoker.blazingjokergame"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.0.1"

        // AndroidX security library uses vector drawables for the crypto UI on older APIs
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps["storeFile"] as? String ?: "")
            storePassword = keystoreProps["storePassword"] as? String ?: ""
            keyAlias = keystoreProps["keyAlias"] as? String ?: ""
            keyPassword = keystoreProps["keyPassword"] as? String ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Required by androidx.security-crypto Tink transitives on API 24
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/proguard/*",
            "META-INF/*.kotlin_module",
        )
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // ── Core ──────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // ── Coroutines for the boot pipeline & async I/O ─────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // ── WebView shell (native gray canvas) ───────────────
    implementation("androidx.webkit:webkit:1.12.1")

    // ── Persistence & secure storage ─────────────────────
    // Use alpha06 — the last release before EncryptedSharedPreferences was
    // marked deprecated; still perfectly safe for our small key set.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // ── Firebase (BOM keeps every messaging transitive in sync) ──
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    // ── AppsFlyer Android SDK (native, not the Flutter plugin) ──
    implementation("com.appsflyer:af-android-sdk:6.16.2")
    implementation("com.android.installreferrer:installreferrer:2.2")

    // ── SplashScreen (Android 12+ splash back-compat) ────
    implementation("androidx.core:core-splashscreen:1.0.1")

    // ── Desugaring for API 24 baseline ───────────────────
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
}
