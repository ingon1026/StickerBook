import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.k3i.adclient"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.k3i.adclient"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // local.properties 의 AD_SERVER_API_KEY 를 BuildConfig 로 노출.
        // 키 없으면 빈 문자열 → 서버에서 401 (UI 에서 친근 메시지로 처리).
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "AD_SERVER_API_KEY",
            "\"${props.getProperty("AD_SERVER_API_KEY") ?: ""}\"",
        )
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

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")

    // HTTP 클라이언트
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 코루틴
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // GIF 표시
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
