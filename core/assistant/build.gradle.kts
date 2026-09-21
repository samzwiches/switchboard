plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.switchboard.core.assistant"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":providers:provider-api"))
    implementation(project(":core:wakeword"))
    implementation(project(":core:actions"))
    implementation(project(":voice:voice-api"))
    implementation(project(":voice:speech-api"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
