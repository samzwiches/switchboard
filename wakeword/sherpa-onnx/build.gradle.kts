plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.switchboard.wakeword.sherpa"
    compileSdk = 36

    defaultConfig { minSdk = 26 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    api(project(":core:wakeword"))
    compileOnly(files("libs/sherpa-onnx-1.13.4.aar"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(files("libs/sherpa-onnx-1.13.4.aar"))
}
