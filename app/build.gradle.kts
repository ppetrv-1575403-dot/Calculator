plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.google.android.calculator"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.google.android.calculator"
        minSdk = 26
        targetSdk = 34
        versionCode = 37
        versionName = "9.2 (941607204)"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.4"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        create("release") {
            // Путь берётся из переменной окружения GitHub Actions

            storeFile = file(System.getenv("KEYSTORE_PATH") ?: "../release-key.jks")
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: findProperty("KEYSTORE_PASSWORD") as String? ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: findProperty("KEY_ALIAS") as String? ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: findProperty("KEY_PASSWORD") as String? ?: ""
        }
    }

    buildTypes {
       debug {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("com.github.MKergall:osmbonuspack:6.9.0")
}