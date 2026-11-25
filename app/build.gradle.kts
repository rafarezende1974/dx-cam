plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "br.com.dxlabs.dxcam"
    compileSdk = 36

    defaultConfig {
        // Mesmo pacote do DX Cam final (br.com.dxlabs.dxcam): este app de
        // diagnostico e a Fase 0; a v1 instala por cima dele.
        applicationId = "br.com.dxlabs.dxcam"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.1-rec"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
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
    implementation("androidx.activity:activity-ktx:1.9.2")
}
