package com.example.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceCaptureGateTest {
    @Test fun `permission already granted never starts capture without Start`() {
        val gate = VoiceCaptureGate()
        assertFalse(gate.canCapture(true))
        gate.resume()
        assertFalse(gate.canCapture(true))
        assertTrue(gate.requestStart())
        assertFalse(gate.canCapture(false))
        assertTrue(gate.canCapture(true))
    }

    @Test fun `Stop cancels a pending Android permission response`() {
        val gate = VoiceCaptureGate()
        gate.requestStart()
        gate.stop()
        assertFalse(gate.canCapture(true))
    }

    @Test fun `background and recreation require another explicit Start`() {
        val gate = VoiceCaptureGate()
        gate.requestStart()
        gate.suspend()
        assertFalse(gate.requestStart())
        assertFalse(gate.canCapture(true))
        gate.resume()
        assertFalse(gate.canCapture(true))
        assertFalse(VoiceCaptureGate().canCapture(true))
    }

    @Test fun `room script mismatch is explicit and grants no adapter`() {
        try { VoiceRoomAdapter.adapt("export const anotherRoom = true;"); fail("Expected mismatch") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Microphone remains stopped")) }
    }

    @Test fun `audio permission origin uses the selected room port`() {
        assertTrue(VoiceRoomAdapter.sameOrigin(DEFAULT_VOICE_URL, "http://127.0.0.1:8790"))
        assertFalse(VoiceRoomAdapter.sameOrigin(DEFAULT_VOICE_URL, "http://127.0.0.1:8792/"))
        assertFalse(VoiceRoomAdapter.sameOrigin(DEFAULT_VOICE_URL, "https://127.0.0.1:8790/"))
    }
}
