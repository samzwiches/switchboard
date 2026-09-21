package com.switchboard.wakeword.sherpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmLevelTest {
    @Test
    fun `silence is zero and louder pcm produces larger normalized level`() {
        val silence = PcmLevel.normalized(ShortArray(100), 100)
        val quiet = PcmLevel.normalized(ShortArray(100) { 500 }, 100)
        val loud = PcmLevel.normalized(ShortArray(100) { 20_000 }, 100)

        assertEquals(0f, silence)
        assertTrue(quiet in 0f..1f)
        assertTrue(loud in 0f..1f)
        assertTrue(loud > quiet)
    }
}
