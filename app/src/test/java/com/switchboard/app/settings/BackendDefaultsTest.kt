package com.switchboard.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendDefaultsTest {
    private fun default(debug: Boolean, emulator: Boolean, saved: String? = null, configured: String = "") =
        resolveBackendUrl(saved, configured, debug, emulator, "http://10.0.2.2:8787", "http://192.168.1.5:8787")

    @Test fun `fresh emulator uses Mac host alias`() {
        assertEquals("http://10.0.2.2:8787", default(true, true))
    }
    @Test fun `fresh phone uses local build LAN address`() {
        assertEquals("http://192.168.1.5:8787", default(true, false))
    }
    @Test fun `release never falls back to development addresses`() {
        assertEquals("", default(false, true))
        assertEquals("", default(false, false))
    }
    @Test fun `explicit and saved addresses are preserved`() {
        assertEquals("https://configured.example", default(true, true, configured = "https://configured.example"))
        assertEquals("https://saved.example", default(true, true, saved = "https://saved.example"))
        assertEquals("", default(true, true, saved = ""))
    }
    @Test fun `standard Android virtual devices are recognized`() {
        assertTrue(isAndroidEmulator("google/sdk_gphone64_arm64/emu64a", "sdk_gphone64_arm64", "ranchu", "sdk_gphone64_arm64"))
    }
}
