package com.switchboard.app.settings

/** Saved choices always win; development defaults are never used in release builds. */
fun resolveBackendUrl(
    savedUrl: String?,
    configuredUrl: String,
    debug: Boolean,
    emulator: Boolean,
    emulatorUrl: String,
    lanUrl: String,
): String = savedUrl ?: configuredUrl.ifBlank {
    if (!debug) "" else if (emulator) emulatorUrl else lanUrl
}

fun isAndroidEmulator(fingerprint: String, model: String, hardware: String, product: String): Boolean =
    fingerprint.startsWith("generic") || fingerprint.startsWith("unknown") ||
        model.contains("Emulator", ignoreCase = true) || model.contains("Android SDK built for") ||
        hardware in setOf("goldfish", "ranchu") || product.startsWith("sdk")
