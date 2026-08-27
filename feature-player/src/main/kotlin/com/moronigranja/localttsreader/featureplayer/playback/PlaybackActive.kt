package com.moronigranja.localttsreader.featureplayer.playback

/**
 * Set by [PlaybackService] for the lifetime of a playback session (started
 * until STOP). The overnight pre-generation worker yields to it — the engine
 * is shared, and a user listening must not compete with a full-book
 * synthesis run for the CPU/RTF budget.
 */
object PlaybackActive {
    @Volatile var isActive: Boolean = false
        private set

    fun markStarted() {
        isActive = true
    }

    fun markStopped() {
        isActive = false
    }
}