plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ccdeaihub.gohome"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ccdeaihub.gohome"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // Repository-only development key. Release packages are signed separately.
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
