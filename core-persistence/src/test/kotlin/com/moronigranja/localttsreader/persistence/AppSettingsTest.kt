package com.moronigranja.localttsreader.persistence

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1: AppSettings mirrors the store, defaults first, writes land everywhere. */
class AppSettingsTest {

    private class FakeSettingsDao : SettingsDao {
        val rows = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = rows[key]
        override suspend fun put(setting: SettingEntity) {
            rows[setting.key] = setting.value
        }
    }

    @Test
    fun defaultsHoldBeforeReload() {
        val settings = AppSettings(SettingsStore(FakeSettingsDao()))
        assertEquals(SettingsStore.DEFAULT_VOICE, settings.state.value.voice)
        assertEquals(ThemeMode.SYSTEM, settings.state.value.theme)
        assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.state.value.threshold, 0.0)
        assertEquals(listOf("eng"), settings.state.value.ocrLanguages)
        assertEquals(emptyList<String>(), settings.state.value.favorites)
    }

    @Test
    fun reloadPicksUpPersistedValues() = runBlocking {
        val dao = FakeSettingsDao()
        val store = SettingsStore(dao)
        store.setVoice("bm_george")
        store.setThemeMode(ThemeMode.DARK)
        store.setMatchThreshold(0.72)
        store.setOcrLanguages(listOf("eng", "spa"))
        store.setFavoriteVoices(listOf("af_heart", "bm_george"))

        val settings = AppSettings(store)
        settings.reload()
        assertEquals("bm_george", settings.state.value.voice)
        assertEquals(ThemeMode.DARK, settings.state.value.theme)
        assertEquals(0.72, settings.state.value.threshold, 0.0)
        assertEquals(listOf("eng", "spa"), settings.state.value.ocrLanguages)
        assertEquals(listOf("af_heart", "bm_george"), settings.state.value.favorites)
    }

    @Test
    fun writesFlowThroughToTheStoreAndHotPath() = runBlocking {
        val dao = FakeSettingsDao()
        val store = SettingsStore(dao)
        val settings = AppSettings(store)

        settings.setVoice("pf_dora")
        settings.setThemeMode(ThemeMode.LIGHT)
        settings.setMatchThreshold(0.5)
        settings.setOcrLanguages(listOf("deu"))

        assertEquals("pf_dora", settings.state.value.voice)
        assertEquals("pf_dora", store.voice())
        assertEquals(ThemeMode.LIGHT, settings.state.value.theme)
        assertEquals(0.5, settings.state.value.threshold, 0.0)
        assertEquals(0.5, store.matchThreshold(), 0.0)
        assertEquals(listOf("deu"), settings.state.value.ocrLanguages)
        assertEquals(listOf("deu"), store.ocrLanguages())
    }

    @Test
    fun toggleFavoriteAddsAndRemoves() = runBlocking {
        val settings = AppSettings(SettingsStore(FakeSettingsDao()))
        settings.toggleFavorite("af_heart")
        assertTrue("af_heart" in settings.state.value.favorites)
        settings.toggleFavorite("af_heart")
        assertFalse("af_heart" in settings.state.value.favorites)
    }
}
