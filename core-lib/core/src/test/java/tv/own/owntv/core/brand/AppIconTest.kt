package tv.own.owntv.core.brand

import org.junit.Assert.assertEquals
import org.junit.Test

class AppIconTest {

    @Test
    fun `every stored name resolves to the single dptv mark`() {
        AppIcon.entries.forEach { assertEquals(AppIcon.DPTV, AppIcon.fromStored(it.name)) }
    }

    @Test
    fun `nothing stored or an unknown name falls back to dptv`() {
        assertEquals(AppIcon.DPTV, AppIcon.fromStored(null))
        assertEquals(AppIcon.DPTV, AppIcon.fromStored("EGGSHELL"))
        assertEquals(AppIcon.DPTV, AppIcon.fromStored("VIOLET"))
        assertEquals(AppIcon.DPTV, AppIcon.DEFAULT)
    }

    @Test
    fun `dptv is MainActivity itself`() {
        // Upgrading users' home-screen icons point at MainActivity, so the default must keep that name.
        assertEquals("", AppIcon.DPTV.activitySuffix)
        assertEquals(AppIcon.entries.size, AppIcon.entries.map { it.activitySuffix }.toSet().size)
    }
}
