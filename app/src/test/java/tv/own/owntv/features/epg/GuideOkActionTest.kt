package tv.own.owntv.features.epg

import org.junit.Assert.assertEquals
import org.junit.Test

/** OK in the guide (v2.6.0, after OwnTV v5.2.1 #300): preview first, OK again watches; any programme of a channel not in the preview previews it first (OwnTV's rule). */
class GuideOkActionTest {
    private val now = 10_000L

    @Test
    fun okOnNowBringsAChannelNotInThePreviewIntoIt() {
        assertEquals(GuideOk.PREVIEW, guideOkAction(9_000, 11_000, now, canCatchup = false, previewOn = true, inPreview = false))
    }

    @Test
    fun okAgainOnTheChannelInThePreviewWatchesIt() {
        assertEquals(GuideOk.WATCH, guideOkAction(9_000, 11_000, now, canCatchup = false, previewOn = true, inPreview = true))
    }

    @Test
    fun withThePreviewOffOkWatchesAtOnce() {
        assertEquals(GuideOk.WATCH, guideOkAction(9_000, 11_000, now, canCatchup = false, previewOn = false, inPreview = false))
    }

    @Test
    fun aRowWithNoProgrammeCountsAsNow() {
        assertEquals(GuideOk.PREVIEW, guideOkAction(null, null, now, canCatchup = false, previewOn = true, inPreview = false))
    }

    @Test
    fun aProgrammeStillToComeOpensItsDetailsOnceItsChannelIsInThePreview() {
        assertEquals(GuideOk.PREVIEW, guideOkAction(12_000, 13_000, now, canCatchup = true, previewOn = true, inPreview = false))
        assertEquals(GuideOk.DETAILS, guideOkAction(12_000, 13_000, now, canCatchup = true, previewOn = true, inPreview = true))
    }

    @Test
    fun aProgrammeThatHasBeenOnPlaysFromTheArchiveOrOpensItsDetails() {
        assertEquals(GuideOk.PREVIEW, guideOkAction(7_000, 8_000, now, canCatchup = true, previewOn = true, inPreview = false))
        assertEquals(GuideOk.CATCHUP, guideOkAction(7_000, 8_000, now, canCatchup = true, previewOn = true, inPreview = true))
        assertEquals(GuideOk.DETAILS, guideOkAction(7_000, 8_000, now, canCatchup = false, previewOn = true, inPreview = true))
    }
}
