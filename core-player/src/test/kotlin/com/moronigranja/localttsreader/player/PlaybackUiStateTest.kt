package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The [PlaybackUiState.generatedAheadFraction] contract (decisions #94,
 * denominator retuned #98): fullness of the pregen cushion against the FIXED
 * [PlaybackUiState.PREGEN_HORIZON_SECONDS] horizon — book time is NOT the
 * denominator anymore (it painted sub-pixel segments on long books, B4
 * finding in #95). Clamped to [0, 1]. */
class PlaybackUiStateTest {

    @Test
    fun `default state has zero generated fraction`() {
        assertEquals(0f, PlaybackUiState().generatedAheadFraction, 0f)
    }

    @Test
    fun `steady-state cushion reads against the horizon`() {
        // 45 s queued ahead (the service's look-ahead target) / 120 s horizon.
        val state = PlaybackUiState(generatedAheadSeconds = 45.0)
        assertEquals(0.375f, state.generatedAheadFraction, 1e-6f)
    }

    @Test
    fun `clamped to 1 when ahead exceeds horizon`() {
        val state = PlaybackUiState(generatedAheadSeconds = 300.0)
        assertEquals(1f, state.generatedAheadFraction, 0f)
    }

    @Test
    fun `fraction is speed and book-length independent`() {
        // Same cushion paints identically on a 27 h book at 2x — the old
        // book-time denominator made this sub-pixel (decisions #95).
        val long = PlaybackUiState(generatedAheadSeconds = 45.0, timeLeftSeconds = 97_000.0, speed = 2.0)
        val short = PlaybackUiState(generatedAheadSeconds = 45.0, timeLeftSeconds = 60.0, speed = 1.0)
        assertEquals(long.generatedAheadFraction, short.generatedAheadFraction, 0f)
    }
}
