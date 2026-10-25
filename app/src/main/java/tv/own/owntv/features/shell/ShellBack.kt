package tv.own.owntv.features.shell

/**
 * Back on the menu (the rail) asks to leave the app (v2.3.3, v2.5.1) — whatever screen is behind it. The
 * shell answers it on the key itself, before any BackHandler: those of the screen behind the rail (the
 * guide's categories, Settings and its pages) are composed deeper than the shell's own and so win over
 * it, which turned Back on the menu into that screen's Back. Not over the full-screen player, while a
 * picker of the shell's is up, or while the prompt itself is (its second Back exits, in its own window).
 * (v2.6.1) Nor while a Sport match or league page is open: the menu is hidden there and Back is the page's.
 */
internal fun railBackAsksToExit(
    railHasFocus: Boolean,
    fullscreen: Boolean,
    pickerOpen: Boolean,
    exitShown: Boolean,
    subPageOpen: Boolean = false,
): Boolean = railHasFocus && !fullscreen && !pickerOpen && !exitShown && !subPageOpen
