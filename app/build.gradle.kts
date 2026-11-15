plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the git tag in CI (v1.2.3 -> 1.2.3); versionCode from the run number.
val versionNameEnv = System.getenv("VERSION_NAME") ?: "0.1.0"
val versionCodeEnv = (System.getenv("VERSION_CODE") ?: "1").toInt()

android {
    namespace = "app.dotplay.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dotplay.companion"
        minSdk = 26
        targetSdk = 35
        versionCode = versionCodeEnv
        versionName = versionNameEnv
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
