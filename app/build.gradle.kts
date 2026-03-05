import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.20"
}

android {
    namespace = "com.alian.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.alian.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "1.0.1"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val localProperties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use { localProperties.load(it) }
        }

        create("release") {
            storeFile = file(localProperties["RELEASE_STORE_FILE"] as? String ?: "debug.keystore")
            storePassword = localProperties["RELEASE_STORE_PASSWORD"] as? String ?: "android"
            keyAlias = localProperties["RELEASE_KEY_ALIAS"] as? String ?: "androiddebugkey"
            keyPassword = localProperties["RELEASE_KEY_PASSWORD"] as? String ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        aidl = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildToolsVersion = "36.1.0"
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.1")

    // Security (Encrypted SharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // kotlinx.serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // OkHttp for API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Shizuku
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // JSON
    implementation("org.json:json:20231013")

    // PyTorch Mobile for voice recognition
    implementation("org.pytorch:pytorch_android:1.13.1")
    implementation("org.pytorch:pytorch_android_torchvision:1.13.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")

    // find aar package from local dir.
    implementation("com.alibaba:dashscope-sdk-java:2.22.3")
    implementation(files("libs/nuisdk-release.aar"))
    implementation(files("libs/fastjson-1.1.46.android.jar"))
    // implementation("com.alibaba.idst:nui-01B:2.6.6@aar")
//    implementation("com.alibaba.idst:nui-01B:2.6.6:debug@aar")

    // Markdown support
    implementation("com.mikepenz:multiplatform-markdown-renderer:0.16.0")
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.16.0")

    // Coil for image loading
    implementation("io.coil-kt:coil-compose:2.5.0")
}
