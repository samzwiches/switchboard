import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val switchboardLocalProperties = Properties().apply {
    val localFile = rootProject.file("local.properties")
    if (localFile.isFile) localFile.inputStream().use(::load)
}
val switchboardBackendUrl = providers.gradleProperty("SWITCHBOARD_BACKEND_URL").orNull
    ?: System.getenv("SWITCHBOARD_BACKEND_URL")
    ?: switchboardLocalProperties.getProperty("SWITCHBOARD_BACKEND_URL")
    ?: ""
fun buildConfigString(value: String): String =
    "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.switchboard.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.switchboard.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.2.0"
        buildConfigField("String", "SWITCHBOARD_BACKEND_URL", buildConfigString(switchboardBackendUrl))
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":core:assistant"))
    implementation(project(":core:wakeword"))
    implementation(project(":wakeword:sherpa-onnx"))
    implementation(files("../wakeword/sherpa-onnx/libs/sherpa-onnx-1.13.4.aar"))
    implementation(project(":core:actions"))
    implementation(project(":providers:provider-api"))
    implementation(project(":providers:openai"))
    implementation(project(":voice:voice-api"))
    implementation(project(":voice:android-tts"))
    implementation(project(":voice:speech-api"))
    implementation(project(":voice:android-speech"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
