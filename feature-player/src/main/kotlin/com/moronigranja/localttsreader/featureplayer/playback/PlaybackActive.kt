package com.moronigranja.localttsreader.featureplayer.playback

/**
 * The single pre-generation admission gate (G2): the pre-generation worker
 * yields to an engaged playback session — the engine is shared, and a
 * synthesis run must never compete with playback (or the post-stop fill) for
 * the CPU/RTF budget.
 *
 * Session-window semantics: set ACTIVE by [PlaybackService] from session
 * start (its start/resume paths) and cleared only when the POST-STOP fill
 * completes — the STOP command alone does NOT end the window, so a yielding
 * worker stays paused while the service synthesizes the post-stop buffer
 * (markStopped fires at fill completion, not at STOP).
 *
 * Deliberately a single boolean, not a refcount: there is exactly one
 * playback surface today. A second concurrent playback surface would need
 * this reworked (refcount note stays a future item).
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