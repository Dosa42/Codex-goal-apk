package com.example.voice

/** Permission results may arrive after Stop, navigation or the activity has left the foreground. */
internal class VoiceCaptureGate {
    var foreground = true
        private set
    var requested = false
        private set

    fun requestStart(): Boolean {
        if (!foreground) return false
        requested = true
        return true
    }

    fun canCapture(nativePermissionGranted: Boolean): Boolean =
        foreground && requested && nativePermissionGranted

    fun stop() { requested = false }
    fun suspend() { requested = false; foreground = false }
    fun resume() { foreground = true } // Returning to the screen never requests capture.
}
