plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.com.celsomartins.audiostretch"
    compileSdk = 35
    buildFeatures {
        prefab = true
    }
    signingConfigs {
        create("persistent") {
            val keystorePath = System.getenv("AUDIOSTRETCH_KEYSTORE_PATH")
            val keystorePassword = System.getenv("AUDIOSTRETCH_KEYSTORE_PASSWORD")
            val aliasValue = System.getenv("AUDIOSTRETCH_KEY_ALIAS")
            val keyPasswordValue = System.getenv("AUDIOSTRETCH_KEY_PASSWORD")
            if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !aliasValue.isNullOrBlank() && !keyPasswordValue.isNullOrBlank()) {
                storeFile = file(keystorePath); storePassword = keystorePassword; keyAlias = aliasValue; keyPassword = keyPasswordValue
            }
        }
    }
    defaultConfig {
        applicationId = "br.com.celsomartins.audiostretch"
        minSdk = 26
        targetSdk = 35
        versionCode = providers.gradleProperty("ciVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("ciVersionName").orNull ?: "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            if (!System.getenv("AUDIOSTRETCH_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("persistent")
        }
        release {
            isMinifyEnabled = false
            if (!System.getenv("AUDIOSTRETCH_KEYSTORE_PATH").isNullOrBlank()) signingConfig = signingConfigs.getByName("persistent")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("com.google.oboe:oboe:1.11.0")
}
