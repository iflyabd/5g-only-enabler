plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.iflyabd.netmodeenabler"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.iflyabd.netmodeenabler"
        minSdk = 28
        targetSdk = 36
        versionCode = 9
        versionName = "1.3.6"
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
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    compileOnly("androidx.preference:preference:1.2.1")
}
