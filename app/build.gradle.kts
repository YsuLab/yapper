plugins {
    id("com.android.application")
}

// Release signing comes from env vars set by CI (from GitHub secrets).
// Never commit a keystore or passwords. See docs/setup-guide.md.
val keystorePath: String? = System.getenv("YAPPER_KEYSTORE_PATH")
val hasReleaseKey = !keystorePath.isNullOrBlank() && file(keystorePath).exists()

// Every CI run gets a higher versionCode, so each APK installs over the last one.
val runNumber: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.ysulab.yapper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ysulab.yapper"
        minSdk = 29
        targetSdk = 36
        versionCode = runNumber
        versionName = "0.0.$runNumber"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("YAPPER_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YAPPER_KEY_ALIAS")
                keyPassword = System.getenv("YAPPER_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
