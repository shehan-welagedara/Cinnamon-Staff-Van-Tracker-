plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

fun env(name: String): String = System.getenv(name) ?: ""

val runNumber = env("GITHUB_RUN_NUMBER").toIntOrNull() ?: 1

android {
    namespace = "com.cinnamon.vantracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cinnamon.vantracker"
        minSdk = 26
        targetSdk = 35
        versionCode = runNumber
        versionName = "1.0.$runNumber"

        // Optional: bake Firebase config into the APK (set as GitHub secrets).
        // When empty, the app asks for the config on first launch.
        buildConfigField("String", "FB_API_KEY", "\"${env("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FB_APP_ID", "\"${env("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FB_PROJECT_ID", "\"${env("FIREBASE_PROJECT_ID")}\"")

        // Optional Google Maps key (GitHub secret MAPS_API_KEY). Empty = OpenStreetMap.
        buildConfigField("String", "MAPS_API_KEY", "\"${env("MAPS_API_KEY")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = env("MAPS_API_KEY")
    }

    signingConfigs {
        create("release") {
            val ksPath = env("SIGNING_KEYSTORE_PATH")
            if (ksPath.isNotEmpty()) {
                storeFile = file(ksPath)
                storePassword = env("SIGNING_STORE_PASSWORD")
                keyAlias = env("SIGNING_KEY_ALIAS")
                keyPassword = env("SIGNING_KEY_PASSWORD")
            } else {
                // Development key committed to the repo so every CI build can update the
                // previous install. Replace with a private key via GitHub secrets for production.
                storeFile = file("dev-signing.jks")
                storePassword = "vantracker"
                keyAlias = "vantracker"
                keyPassword = "vantracker"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
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
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // OpenStreetMap (open source, no API key / billing)
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Google Maps (only used when a MAPS_API_KEY is provided at build time)
    implementation("com.google.maps.android:maps-compose:6.1.0")

    // Frosted "liquid glass" blur behind bars and cards (Apache 2.0)
    implementation("dev.chrisbanes.haze:haze:1.2.2")
}
