package tv.own.owntv.features.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v2.5.1: Back on the menu asks to leave the app over any screen, Settings and the guide included. */
class ShellBackTest {
    @Test
    fun backOnTheMenuAsksToExit() {
        assertTrue(railBackAsksToExit(railHasFocus = true, fullscreen = false, pickerOpen = false, exitShown = false))
    }

    @Test
    fun backElsewhereIsTheScreensOwn() {
        assertFalse(railBackAsksToExit(railHasFocus = false, fullscreen = false, pickerOpen = false, exitShown = false))
    }

    @Test
    fun notOverThePlayerAPickerOrThePromptItself() {
        assertFalse(railBackAsksToExit(railHasFocus = true, fullscreen = true, pickerOpen = false, exitShown = false))
        assertFalse(railBackAsksToExit(railHasFocus = true, fullscreen = false, pickerOpen = true, exitShown = false))
        assertFalse(railBackAsksToExit(railHasFocus = true, fullscreen = false, pickerOpen = false, exitShown = true))
    }
}
