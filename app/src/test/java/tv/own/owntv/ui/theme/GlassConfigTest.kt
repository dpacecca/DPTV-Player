package tv.own.owntv.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassConfigTest {

    @Test
    fun `stored preset name wins and resolves its material values`() {
        val preset = GlassPreset.fromStored("TINTED", customAlpha = 0.21f, customBlur = 0.32f)

        assertEquals(GlassPreset.TINTED, preset)
        assertEquals(0.74f, preset.resolveAlpha(0.21f), 0f)
        assertEquals(0.88f, preset.resolveBlur(0.32f), 0f)
    }

    @Test
    fun `missing preset recognizes balanced values`() {
        assertEquals(
            GlassPreset.BALANCED,
            GlassPreset.fromStored(null, customAlpha = 0.56f, customBlur = 0.78f),
        )
    }

    @Test
    fun `legacy user values migrate to custom without being changed`() {
        val alpha = 0.63f
        val blur = 0.41f
        val preset = GlassPreset.fromStored(null, customAlpha = alpha, customBlur = blur)
        val config = GlassConfig.fromBitmask(
            bits = 1 shl GlassSurface.PANELS.ordinal,
            alpha = alpha,
            blurStrength = blur,
            preset = preset,
        )

        assertEquals(GlassPreset.CUSTOM, preset)
        assertEquals(alpha, config.alpha, 0f)
        assertEquals(blur, config.blurStrength, 0f)
    }

    @Test
    fun `surface bitmask round trip preserves every selected role`() {
        val selected = setOf(GlassSurface.SIDEBAR, GlassSurface.DIALOGS, GlassSurface.MINI_PLAYER)
        val encoded = GlassConfig(scope = selected, preset = GlassPreset.CUSTOM).toBitmask()
        val decoded = GlassConfig.fromBitmask(encoded, preset = GlassPreset.CUSTOM)

        assertEquals(selected, decoded.scope)
        assertTrue(decoded.enabled)
        assertTrue(decoded.isGlassy(GlassSurface.DIALOGS))
        assertFalse(decoded.isGlassy(GlassSurface.PREVIEW))
    }

    @Test
    fun `preset values resolve when restoring a bitmask`() {
        val config = GlassConfig.fromBitmask(
            bits = 1 shl GlassSurface.CARDS.ordinal,
            alpha = 0.12f,
            blurStrength = 0.23f,
            preset = GlassPreset.CLEAR,
        )

        assertEquals(GlassPreset.CLEAR, config.preset)
        assertEquals(0.38f, config.alpha, 0f)
        assertEquals(0.62f, config.blurStrength, 0f)
    }
}
