package tv.own.owntv.core.sync.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.settings.EpgAutoRefresh
import tv.own.owntv.core.settings.EpgRefresh
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.sync.SyncContentTypes
import tv.own.owntv.core.sync.SyncManager

/** Which content each trigger syncs (v2.5.3), and which sources it takes in. */
class ScheduledContentTypesTest {

    private fun source(type: SourceType = SourceType.XTREAM, movies: Boolean = true, series: Boolean = true) = SourceEntity(
        name = "p", type = type, url = "http://x", syncLive = true, syncMovies = movies, syncSeries = series,
    )

    @Test
    fun liveTimesSyncLiveOnlyAndTheDailyRunFilmsAndShowsOnly() {
        assertEquals(SyncContentTypes(live = true, movies = false, series = false), ScheduledKind.LIVE.contentTypes)
        assertEquals(SyncContentTypes(live = false, movies = true, series = true), ScheduledKind.VOD.contentTypes)
        assertNull(ScheduledKind.EPG.contentTypes)
    }

    @Test
    fun aManualSyncStillCoversEverything() {
        // Sync now / Resync / adding a playlist pass the source's own switches, all on here.
        val all = SyncContentTypes.enabledOf(source())
        assertEquals(SyncContentTypes(), all.effectiveFor(source()))
    }

    @Test
    fun theTimedPassesRespectEachPlaylistsSwitches() {
        val noSeries = source(series = false)
        assertEquals(
            SyncContentTypes(live = false, movies = true, series = false),
            ScheduledKind.VOD.contentTypes!!.intersect(SyncContentTypes.enabledFor(noSeries)),
        )
        val liveOnly = source(movies = false, series = false)
        assertFalse(ScheduledKind.VOD.contentTypes!!.intersect(SyncContentTypes.enabledFor(liveOnly)).hasAny)
        // A local backup is live-only whatever it stores.
        assertFalse(ScheduledKind.VOD.contentTypes!!.intersect(SyncContentTypes.enabledFor(source(SourceType.LOCAL_BACKUP))).hasAny)
    }

    @Test
    fun offStaysOutAndEveryOlderModeMeansTheSyncTimes() {
        assertFalse(SyncScheduler.playlistIncluded(PlaylistRefresh.OFF))
        assertTrue(SyncScheduler.playlistIncluded(null))
        assertTrue(SyncScheduler.playlistIncluded(PlaylistRefresh.STARTUP))
        assertTrue(SyncScheduler.playlistIncluded(PlaylistRefresh(PlaylistAutoRefresh.HOURS_6)))
        assertTrue(SyncScheduler.playlistIncluded(PlaylistRefresh(PlaylistAutoRefresh.MANUAL, 7)))
        assertFalse(SyncScheduler.epgIncluded(EpgRefresh.OFF))
        assertTrue(SyncScheduler.epgIncluded(EpgRefresh(EpgAutoRefresh.HOURS_48)))
        assertTrue(SyncScheduler.epgIncluded(null))
    }

    @Test
    fun aLowSpecDeviceRunsThePhasesOneAtATime() {
        assertEquals(1, tv.own.owntv.core.sync.XtreamSyncer.phaseConcurrency(lowSpec = true))
        assertEquals(3, tv.own.owntv.core.sync.XtreamSyncer.phaseConcurrency(lowSpec = false))
    }

    @Test
    fun aTimedPassStampsASourceThatSyncedBeforeButNeverAFirstImport() {
        val target = SyncContentTypes()
        val live = ScheduledKind.LIVE.contentTypes!!
        assertTrue(SyncManager.shouldStampSynced(live, target, lastSyncAt = 1L))
        assertTrue(SyncManager.shouldStampSynced(ScheduledKind.VOD.contentTypes!!, target, lastSyncAt = 1L))
        // A staged first import still waits for its remainder.
        assertFalse(SyncManager.shouldStampSynced(live, target, lastSyncAt = null))
        assertTrue(SyncManager.shouldStampSynced(target, target, lastSyncAt = null))
        assertFalse(SyncManager.shouldStampSynced(SyncContentTypes(false, false, false), target, lastSyncAt = 1L))
    }
}
