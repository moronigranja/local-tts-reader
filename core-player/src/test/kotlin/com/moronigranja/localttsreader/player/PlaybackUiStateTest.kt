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

    @Test
    fun `passage indicator label is null when the book has no passages`() {
        assertEquals(null, PlaybackUiState.passageIndicatorLabel(0, 0))
        assertEquals(null, PlaybackUiState.passageIndicatorLabel(3, 0))
    }

    @Test
    fun `passage indicator label renders one-based index and percent`() {
        assertEquals("Passage 1/10 (0%)", PlaybackUiState.passageIndicatorLabel(0, 10))
        assertEquals("Passage 5/10 (40%)", PlaybackUiState.passageIndicatorLabel(4, 10))
        assertEquals("Passage 138/5012 (2%)", PlaybackUiState.passageIndicatorLabel(137, 5012))
    }

    @Test
    fun `passage indicator percent clamps at one hundred`() {
        // The index is 0-based so the last passage can never exceed 100; the
        // clamp protects the label's shape however the caller feeds it.
        // The last in-range passage is 9 (0-based) — 90%, not 100: percent
        // is index/count, and the line renders 1-based.
        assertEquals("Passage 10/10 (90%)", PlaybackUiState.passageIndicatorLabel(9, 10))
        // The clamp only bites when the caller feeds an index past the end.
        assertEquals("Passage 11/10 (100%)", PlaybackUiState.passageIndicatorLabel(10, 10))
    }
}
