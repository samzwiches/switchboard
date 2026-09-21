plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.switchboard.voice.androidtts"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":voice:voice-api"))
    implementation(libs.kotlinx.coroutines.android)
}

