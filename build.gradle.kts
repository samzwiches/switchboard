buildscript {
    dependencies {
        // AGP 9 provides Kotlin support. Pinning KGP keeps the Compose compiler aligned.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
}

