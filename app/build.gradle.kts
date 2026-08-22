plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.joymouse.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joymouse.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "2.1"
    }

    signingConfigs {
        create("release") {
            // 本地开发密钥；可用环境变量覆盖（CI/发布场景请改用安全注入）
            storeFile = file(System.getenv("JOYMOUSE_KEYSTORE") ?: "/home/yupd/android-dev/joymouse-release.jks")
            storePassword = System.getenv("JOYMOUSE_KEYSTORE_PASS") ?: "joymouse2025"
            keyAlias = System.getenv("JOYMOUSE_KEY_ALIAS") ?: "joymouse"
            keyPassword = System.getenv("JOYMOUSE_KEY_PASS") ?: "joymouse2025"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // JVM 单元测试：用真实 org.json 覆盖 Android stub（无需模拟器）
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
