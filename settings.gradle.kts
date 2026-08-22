pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Switchboard"

include(
    ":app",
    ":core:assistant",
    ":core:wakeword",
    ":wakeword:sherpa-onnx",
    ":core:actions",
    ":providers:provider-api",
    ":providers:openai",
    ":voice:voice-api",
    ":voice:android-tts",
)
