plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.switchboard.voice.androidspeech"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":voice:speech-api"))
    implementation(libs.kotlinx.coroutines.android)
}
