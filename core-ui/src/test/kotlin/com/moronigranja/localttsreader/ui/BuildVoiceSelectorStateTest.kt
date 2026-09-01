package com.moronigranja.localttsreader.ui

import com.moronigranja.localttsreader.player.AuditionStage
import com.moronigranja.localttsreader.player.AuditionUiState
import com.moronigranja.localttsreader.tts.kokoro.KokoroVoiceMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C2: the single shared selector-state builder (decisions #102.4) — exactly
 * one row carries the selection indicator, favorites are independent of
 * selection, the persistent summary names the saved voice, and a saved voice
 * absent from the catalog renders as unavailable instead of an all-unselected
 * list. Readiness and the audition stage flow straight through.
 */
class BuildVoiceSelectorStateTest {
    private val voice = "af_heart"
    private val other = "af_bella"

    @Test
    fun `exactly one row is selected and the summary names it`() {
        val state =
            buildVoiceSelectorState(
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                ready = true,
                audition = AuditionUiState(),
            )
        assertEquals(listOf(voice), state.rows.filter { it.selected }.map { it.name })
        assertEquals("Selected voice: $voice", state.summary)
        assertNull(state.unavailableSavedVoice)
    }

    @Test
    fun `favorite is independent of selection`() {
        // other voice is the favorite, but voice is selected — the star and
        // the radio must not imply each other.
        val state =
            buildVoiceSelectorState(
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = setOf(other),
                ready = true,
                audition = AuditionUiState(),
            )
        val row = state.rows.first { it.name == other }
        assertTrue(row.favorite)
        assertTrue(!row.selected)
        val selected = state.rows.first { it.name == voice }
        assertTrue(!selected.favorite)
        assertTrue(selected.selected)
    }

    @Test
    fun `saved voice absent from catalog is surfaced as unavailable`() {
        val state =
            buildVoiceSelectorState(
                voices = KokoroVoiceMetadata.all,
                selectedVoice = "zz_ghost",
                favorites = emptySet(),
                ready = true,
                audition = AuditionUiState(),
            )
        assertEquals("zz_ghost", state.unavailableSavedVoice)
        assertEquals("", state.summary)
        assertTrue(state.rows.none { it.selected })
    }

    @Test
    fun `missing packs mark every row unready`() {
        val state =
            buildVoiceSelectorState(
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                ready = false,
                audition = AuditionUiState(),
            )
        assertTrue(state.rows.isNotEmpty())
        assertTrue(state.rows.all { !it.ready })
    }

    @Test
    fun `audition stage maps onto the matching voice row only`() {
        val audition = AuditionUiState(voice, AuditionStage.Generating)
        val state =
            buildVoiceSelectorState(
                voices = KokoroVoiceMetadata.all,
                selectedVoice = voice,
                favorites = emptySet(),
                ready = true,
                audition = audition,
            )
        assertEquals(VoicePreviewUi.Generating, state.rows.first { it.name == voice }.preview)
        assertTrue(state.rows.first { it.name == other }.preview is VoicePreviewUi.Idle)
    }
}
