plugins {
    id("com.android.application")
}

android {
    namespace = "com.clipbal.fix"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.clipbal.fix"
        minSdk = 34
        targetSdk = 36
        versionCode = 4
        versionName = "2.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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

    packaging {
        resources {
            // LSPosed 现代模块元数据必须原样打包进 APK
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // libxposed API 由框架在运行时提供，绝不能 implementation 打进 APK
    compileOnly("io.github.libxposed:api:102.0.0")
}
