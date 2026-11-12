plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "site.whpp.schedule"
    compileSdk = 34

    defaultConfig {
        applicationId = "site.whpp.schedule"
        minSdk = 24
        targetSdk = 34
        versionCode = 12
        versionName = "4.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
