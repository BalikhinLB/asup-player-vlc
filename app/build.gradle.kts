plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.lb.asupplayer"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.lb.asupplayer"
        minSdk = 31
        targetSdk = 36
        versionCode = 5
        versionName = "0.0.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.androidx.activity)
    implementation(libs.androidx.core.ktx)
    implementation(libs.libvlc.all)
}
