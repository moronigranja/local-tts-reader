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
        assertEquals(SettingsStore.DEFAULT_VOICE, settings.voice)
        assertEquals(ThemeMode.SYSTEM, settings.themeMode.value)
        assertEquals(SettingsStore.DEFAULT_MATCH_THRESHOLD, settings.matchThreshold, 0.0)
        assertEquals(listOf("eng"), settings.ocrLanguages)
        assertEquals(emptyList<String>(), settings.favoriteVoices)
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
        assertEquals("bm_george", settings.voice)
        assertEquals(ThemeMode.DARK, settings.themeMode.value)
        assertEquals(0.72, settings.matchThreshold, 0.0)
        assertEquals(listOf("eng", "spa"), settings.ocrLanguages)
        assertEquals(listOf("af_heart", "bm_george"), settings.favoriteVoices)
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

        assertEquals("pf_dora", settings.voice)
        assertEquals("pf_dora", store.voice())
        assertEquals(ThemeMode.LIGHT, settings.themeMode.value)
        assertEquals(0.5, settings.matchThreshold, 0.0)
        assertEquals(0.5, store.matchThreshold(), 0.0)
        assertEquals(listOf("deu"), settings.ocrLanguages)
        assertEquals(listOf("deu"), store.ocrLanguages())
    }

    @Test
    fun toggleFavoriteAddsAndRemoves() = runBlocking {
        val settings = AppSettings(SettingsStore(FakeSettingsDao()))
        settings.toggleFavorite("af_heart")
        assertTrue("af_heart" in settings.favoriteVoices)
        settings.toggleFavorite("af_heart")
        assertFalse("af_heart" in settings.favoriteVoices)
    }
}
