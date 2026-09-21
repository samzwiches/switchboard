import java.util.Properties
import java.net.URI

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
val switchboardDebugPort = switchboardLocalProperties.getProperty("SWITCHBOARD_DEBUG_PORT", "8787")
    .toInt().also { require(it in 1024..65535) }
val switchboardDebugLanUrl = switchboardLocalProperties.getProperty("SWITCHBOARD_DEBUG_LAN_URL", "")
val switchboardDebugHosts = listOf("10.0.2.2", "127.0.0.1", "localhost") +
    listOf(switchboardDebugLanUrl, switchboardBackendUrl).mapNotNull {
        runCatching { URI(it).takeIf { uri -> uri.scheme == "http" }?.host }.getOrNull()
    }
abstract class GenerateDebugNetworkSecurity : DefaultTask() {
    @get:Input abstract val hosts: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun generate() {
        val xml = outputDirectory.file("xml/switchboard_debug_network_security.xml").get().asFile
        xml.parentFile.mkdirs()
        val domains = hosts.get().distinct()
        require(domains.all { it.matches(Regex("[A-Za-z0-9.:-]+")) })
        xml.writeText("""<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="false" />
    <domain-config cleartextTrafficPermitted="true">
${domains.joinToString("\n") { "        <domain includeSubdomains=\"false\">$it</domain>" }}
    </domain-config>
</network-security-config>
""")
    }
}
val generateDebugNetworkSecurity = tasks.register<GenerateDebugNetworkSecurity>("generateDebugNetworkSecurity") {
    hosts.set(switchboardDebugHosts)
    outputDirectory.set(layout.buildDirectory.dir("generated/switchboardDebug/res"))
}
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
        buildConfigField("String", "DEBUG_EMULATOR_BACKEND_URL", "\"\"")
        buildConfigField("String", "DEBUG_LAN_BACKEND_URL", "\"\"")
    }

    buildTypes {
        getByName("debug") {
            buildConfigField("String", "DEBUG_EMULATOR_BACKEND_URL", buildConfigString("http://10.0.2.2:$switchboardDebugPort"))
            buildConfigField("String", "DEBUG_LAN_BACKEND_URL", buildConfigString(switchboardDebugLanUrl))
        }
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

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateDebugNetworkSecurity, GenerateDebugNetworkSecurity::outputDirectory,
        )
    }
}
