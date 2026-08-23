plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "info.plateaukao.sharik"
    compileSdk = 36

    defaultConfig {
        applicationId = "info.plateaukao.sharik"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release keystore stays out of the repo: SHARIK_KEYSTORE(+_PASSWORD, _KEY_ALIAS, _KEY_PASSWORD)
    // from the environment or ~/.gradle/gradle.properties; falls back to the debug key.
    val ksPath = System.getenv("SHARIK_KEYSTORE") ?: findProperty("SHARIK_KEYSTORE") as String?
    if (ksPath != null && file(ksPath).exists()) {
        signingConfigs.create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("SHARIK_KEYSTORE_PASSWORD") ?: findProperty("SHARIK_KEYSTORE_PASSWORD") as String?
            keyAlias = System.getenv("SHARIK_KEY_ALIAS") ?: findProperty("SHARIK_KEY_ALIAS") as String?
            keyPassword = System.getenv("SHARIK_KEY_PASSWORD") ?: findProperty("SHARIK_KEY_PASSWORD") as String?
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
    kotlinOptions { jvmTarget = "17" }

    packaging {
        resources.excludes += setOf("kotlin/**", "META-INF/*.version", "META-INF/version-control-info.textproto")
    }
}

// No dependencies: framework views + Kotlin stdlib only.
