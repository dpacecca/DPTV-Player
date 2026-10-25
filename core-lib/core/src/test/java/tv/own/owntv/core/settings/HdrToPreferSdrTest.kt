package tv.own.owntv.core.settings

import androidx.datastore.preferences.core.mutablePreferencesOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.Keys

/** The retired HDR switch becomes Prefer SDR once (core 1.0.72). */
class HdrToPreferSdrTest {

    @Test
    fun `HDR turned off starts with Prefer SDR on`() {
        val prefs = mutablePreferencesOf(Keys.LEGACY_HDR_ENABLED to false)
        SettingsRepository.hdrToPreferSdr(prefs)
        assertEquals(true, prefs[Keys.PREFER_SDR])
        assertNull(prefs[Keys.LEGACY_HDR_ENABLED])
    }

    @Test
    fun `HDR left on keeps the Prefer SDR default`() {
        val prefs = mutablePreferencesOf(Keys.LEGACY_HDR_ENABLED to true)
        SettingsRepository.hdrToPreferSdr(prefs)
        assertNull(prefs[Keys.PREFER_SDR])
        assertNull(prefs[Keys.LEGACY_HDR_ENABLED])
    }

    @Test
    fun `a Prefer SDR already chosen is kept`() {
        val prefs = mutablePreferencesOf(Keys.LEGACY_HDR_ENABLED to false, Keys.PREFER_SDR to false)
        SettingsRepository.hdrToPreferSdr(prefs)
        assertEquals(false, prefs[Keys.PREFER_SDR])
        assertNull(prefs[Keys.LEGACY_HDR_ENABLED])
    }

    @Test
    fun `nothing stored changes nothing`() {
        val prefs = mutablePreferencesOf()
        SettingsRepository.hdrToPreferSdr(prefs)
        assertNull(prefs[Keys.PREFER_SDR])
    }
}
