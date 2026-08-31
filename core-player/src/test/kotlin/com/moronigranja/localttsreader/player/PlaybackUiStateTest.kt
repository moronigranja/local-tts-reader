package com.moronigranja.localttsreader.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The [PlaybackUiState.generatedAheadFraction] contract (decisions #94):
 * book-time denominator (timeLeftSeconds is wall-clock at speed, so
 * timeLeft * speed is book time), clamped. */
class PlaybackUiStateTest {

    @Test
    fun `default state has zero generated fraction`() {
        assertEquals(0f, PlaybackUiState().generatedAheadFraction, 0f)
    }

    @Test
    fun `quarter of remaining at 1x`() {
        val state = PlaybackUiState(generatedAheadSeconds = 10.0, timeLeftSeconds = 40.0, speed = 1.0)
        assertEquals(0.25f, state.generatedAheadFraction, 1e-6f)
    }

    @Test
    fun `speed converts wall-clock remaining to book-time`() {
        // 20 wall seconds at 2x = 40 book seconds; 10 ahead / 40 = 0.25.
        val state = PlaybackUiState(generatedAheadSeconds = 10.0, timeLeftSeconds = 20.0, speed = 2.0)
        assertEquals(0.25f, state.generatedAheadFraction, 1e-6f)
    }

    @Test
    fun `clamped to 1 when ahead exceeds remaining`() {
        val state = PlaybackUiState(generatedAheadSeconds = 100.0, timeLeftSeconds = 40.0, speed = 1.0)
        assertEquals(1f, state.generatedAheadFraction, 0f)
    }

    @Test
    fun `zero remaining yields zero`() {
        val state = PlaybackUiState(generatedAheadSeconds = 10.0, timeLeftSeconds = 0.0, speed = 1.0)
        assertEquals(0f, state.generatedAheadFraction, 0f)
    }
}
