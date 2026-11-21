plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.farazkhan.footprint"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.farazkhan.footprint"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.4.1"
    }

    buildFeatures { buildConfig = true }

    // Two editions from one codebase. They differ only in Reels/Shorts blocking, which needs
    // Android's Accessibility API and its own Play Console declaration.
    //   full:  everything. What you install on your phone (the default in Android Studio).
    //   store: the same app without the Accessibility service, for a simpler first Play review.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "REELS", "true")
        }
        create("store") {
            dimension = "edition"
            buildConfigField("boolean", "REELS", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // The dashboard (HTML, JS and fonts) ships inside the app; nothing is fetched from the internet.
    sourceSets["main"].assets.srcDir("../../web")
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Step recording by Google Play services, on the phone only (RecordedSteps.kt). No account, no internet.
    implementation("com.google.android.gms:play-services-fitness:21.2.0")
    // Play services pulls in androidx.activity 1.0.0, which Play Console flags as outdated; use a current one.
    implementation("androidx.activity:activity:1.9.3")
}
