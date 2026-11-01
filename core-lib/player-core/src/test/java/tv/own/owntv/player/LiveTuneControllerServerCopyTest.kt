package tv.own.owntv.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.player.EnginePreference
import tv.own.owntv.core.stalker.ReconnectUrlProvider
import tv.own.owntv.core.timeshift.TimeshiftBuffer
import tv.own.owntv.core.timeshift.TimeshiftServer
import tv.own.owntv.core.timeshift.TimeshiftSession
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * (v2.5.1) The recording server's copy and the preview's speed: the provider's stream goes on screen at
 * once, the server's copy is made beside it only where the playlist allows two streams, and the picture
 * moves onto the copy only for a pause or a rewind. Virtual time, fake engines, a fake server copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveTuneControllerServerCopyTest {

    private class Engines : LiveEngines {
        val log = mutableListOf<String>()
        override var exoUrl: String? = null
        override val exoIsHls = false
        override var exoFailed = false
        override var mpvHasStream = false
        override val exoUserPaused = MutableStateFlow(false)
        var position = 0L

        override fun exoPlay(url: String, muted: Boolean, request: LiveRequest) { exoUrl = url; log += "exo:$url" }
        override fun exoSetMuted(muted: Boolean) { log += if (muted) "exo-mute" else "exo-unmute" }
        override fun exoPause() { log += "exo-pause"; exoUserPaused.value = true }
        override fun exoStop() { exoUrl = null; log += "exo-stop"; exoUserPaused.value = false }
        override fun exoReleaseUhdDecoder() {}
        override fun exoAbandon(reason: String) { log += "exo-abandon" }
        override suspend fun watchExo(
            channelName: String,
            stillOurs: () -> Boolean,
            handOver: suspend (String) -> Unit,
            onOpened: () -> Unit,
            postponeDeadline: (Long) -> Unit,
            log: (String) -> Unit,
        ) { CompletableDeferred<Unit>().await() }
        override fun mpvPlay(url: String, request: LiveRequest) { mpvHasStream = true; log += "mpv:$url" }
        override fun mpvStop() { mpvHasStream = false }
        override suspend fun mpvStopAndAwaitRelease() { mpvHasStream = false }
        override fun mpvAbandon(reason: String) {}
        override suspend fun awaitMpvOutcome(timeoutMs: Long): MpvOutcome? =
            withTimeoutOrNull(timeoutMs) { CompletableDeferred<MpvOutcome>().await() }
        override fun setReconnectProvider(provider: ReconnectUrlProvider?) {}
        override fun positionMs(onExo: Boolean): Long = position
    }

    /** A copy the server is keeping: pieces of 2 s from [startWallMs]. */
    private class FakeCopy(override val channelKey: String, private val startWallMs: Long) : TimeshiftBuffer {
        override val token: String = "copy" + channelKey.replace(":", "x") + startWallMs
        override val container = TimeshiftSession.Container.TS
        override val isClosed = false
        override var readingFromWallMs: Long? = null
        override fun open(fromIndex: Long?): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun pieceAt(wallMs: Long): Long = startWallMs + ((wallMs - startWallMs).coerceAtLeast(0) / 2_000) * 2_000
        override fun oldestWallMs(): Long = startWallMs
        override fun playableEdgeWallMs(): Long = startWallMs + 60_000
        override fun gaps(): List<LongRange> = emptyList()
    }

    private class Host(private val scope: TestScope, val maxConnections: Int) : LiveTuneController.Host {
        val asked = mutableListOf<Long>()
        /** How long the server takes to cut its first piece. */
        var firstPieceMs = 3_000L
        /** What the engines had been told when the server was asked, to check the order. */
        var engineLogAtAsk: List<String>? = null
        var engines: Engines? = null

        override suspend fun sourceOf(sourceId: Long) = SourceEntity(
            id = sourceId, name = "p", type = SourceType.XTREAM, url = "http://panel.invalid", maxConnections = maxConnections, preferHls = false,
        )
        override fun needsResolve(source: SourceEntity?) = false
        override suspend fun resolve(source: SourceEntity, cmd: String): String? = null
        override suspend fun enginePin(channel: ChannelEntity): Boolean? = null
        override suspend fun pin(channel: ChannelEntity, onMpv: Boolean) {}
        override suspend fun globalPreference() = EnginePreference.EXO_FIRST
        override suspend fun globalBudgetSecs() = 0
        override fun meta(channel: ChannelEntity) = MediaMeta(title = channel.name)
        override fun recordLadderEvent(onExo: Boolean, reason: PlayerFailureReason, detail: String) {}
        override fun nowMs(): Long = scope.testScheduler.currentTime
        override fun wallNowMs(): Long = WALL_BASE + scope.testScheduler.currentTime
        override suspend fun timeshiftWindowMinutes(): Int = 15
        override suspend fun serverTimeshiftAvailable(channel: ChannelEntity, source: SourceEntity?) = true
        /** The server answers that it cannot keep the channel. */
        var refuse = false
        override suspend fun openServerTimeshift(channel: ChannelEntity, source: SourceEntity?, windowMinutes: Int, channelKey: String): TimeshiftBuffer? {
            asked += channel.id
            engineLogAtAsk = engines?.log?.toList()
            val startedAt = wallNowMs()
            kotlinx.coroutines.delay(firstPieceMs)
            if (refuse) return null
            return FakeCopy(channelKey, startedAt).also { TimeshiftServer.register(it) }
        }
    }

    private fun channel(id: Long) = ChannelEntity(
        id = id,
        sourceId = 1,
        name = "ch$id",
        streamUrl = "http://panel.invalid/live/$id.ts",
        remoteId = "$id",
    )

    private fun TestScope.setUp(maxConnections: Int): Triple<LiveTuneController, Engines, Host> {
        val engines = Engines()
        val host = Host(this, maxConnections).also { it.engines = engines }
        return Triple(LiveTuneController(backgroundScope, engines, host), engines, host)
    }

    @Test
    fun `the preview plays the provider's stream at once and the server's copy follows beside it`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 2)
        c.preview(channel(1), muted = true)
        runCurrent()
        assertEquals("on screen before any copy is asked for", listOf("exo:${channel(1).streamUrl}"), engines.log)
        assertTrue(host.asked.isEmpty())

        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + 1)
        assertEquals(listOf(1L), host.asked)
        advanceTimeBy(host.firstPieceMs + 1)
        assertNotNull("rewind is offered once the copy is ready", c.localTimeshift.value)
        assertEquals("the picture never reloaded", listOf("exo:${channel(1).streamUrl}"), engines.log)

        // OK: full screen promotes the same stream, with the copy still beside it.
        c.tune(channel(1))
        runCurrent()
        assertEquals(listOf("exo:${channel(1).streamUrl}", "exo-unmute"), engines.log)
        assertEquals(listOf(1L), host.asked)
        assertNotNull(c.localTimeshift.value)
    }

    @Test
    fun `resuming after a pause continues from the copy at the paused moment`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 3)
        c.tune(channel(2))
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1_000)
        assertNotNull(c.localTimeshift.value)
        val pausedAt = host.wallNowMs()
        engines.exoUserPaused.value = true
        runCurrent()
        advanceTimeBy(30_000)
        engines.exoUserPaused.value = false
        runCurrent()
        val copy = c.localTimeshift.value!!.session
        val last = engines.log.last()
        assertTrue("onto the copy: $last", last.startsWith("exo:") && TimeshiftServer.isLocal(last.removePrefix("exo:")))
        assertEquals(TimeshiftServer.urlFor(copy, copy.pieceAt(pausedAt - LiveTuneController.DIRECT_BEHIND_COPY_MS)), last.removePrefix("exo:"))
    }

    @Test
    fun `a short pause is left to the engine's own buffer`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 2)
        c.tune(channel(3))
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1_000)
        val before = engines.log.toList()
        engines.exoUserPaused.value = true
        runCurrent()
        advanceTimeBy(2_000)
        engines.exoUserPaused.value = false
        runCurrent()
        assertEquals(before, engines.log)
        assertNotNull(c.localTimeshift.value)
    }

    @Test
    fun `browsing through channels asks the server for one copy, the one the user stays on`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 2)
        c.preview(channel(10), muted = true)
        runCurrent()
        advanceTimeBy(400)
        c.preview(channel(11), muted = true)
        runCurrent()
        advanceTimeBy(400)
        c.preview(channel(12), muted = true)
        runCurrent()
        assertEquals(3, engines.log.count { it.startsWith("exo:") })
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1)
        assertEquals(listOf(12L), host.asked)
        assertEquals("1:12", c.localTimeshift.value!!.session.channelKey)
    }

    @Test
    fun `a copy the server answers after the user moved on is not kept`() = runTest {
        val (c, _, host) = setUp(maxConnections = 2)
        c.preview(channel(20), muted = true)
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + 500) // the server is cutting channel 20's first piece
        assertEquals(listOf(20L), host.asked)
        c.preview(channel(21), muted = true)
        advanceTimeBy(host.firstPieceMs) // 20's copy arrives now; 21's is still settling
        runCurrent()
        assertNull(c.localTimeshift.value)
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs)
        assertEquals("1:21", c.localTimeshift.value!!.session.channelKey)
    }

    @Test
    fun `a one-stream playlist goes full screen without reloading and copies only from a pause`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 1)
        c.preview(channel(30), muted = true)
        runCurrent()
        assertEquals(listOf("exo:${channel(30).streamUrl}"), engines.log)
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1)
        assertTrue("never a second stream beside the preview", host.asked.isEmpty())
        assertNull(c.localTimeshift.value)

        // (DPTV v2.9.2) Full screen promotes the preview: no stop, no server copy, nothing reloads.
        c.tune(channel(30))
        runCurrent()
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1)
        assertEquals(listOf("exo:${channel(30).streamUrl}", "exo-unmute"), engines.log)
        assertTrue(host.asked.isEmpty())
        assertNull(c.localTimeshift.value)

        // A pause: the provider's stream stops before the server connects, and the copy waits paused.
        val pausedAt = host.wallNowMs()
        engines.exoUserPaused.value = true
        runCurrent()
        assertEquals(listOf(30L), host.asked)
        assertEquals(listOf("exo:${channel(30).streamUrl}", "exo-unmute", "exo-stop"), host.engineLogAtAsk)
        advanceTimeBy(host.firstPieceMs + 1)
        val copy = c.localTimeshift.value!!.session
        assertEquals(pausedAt, copy.oldestWallMs())
        val loaded = engines.log[engines.log.size - 2]
        assertTrue("the copy, from the pause: $loaded", TimeshiftServer.isLocal(loaded.removePrefix("exo:")))
        assertEquals("exo-pause", engines.log.last())
        assertTrue(engines.exoUserPaused.value)

        // Play continues from the copy as it is: nothing else is opened.
        val before = engines.log.toList()
        engines.exoUserPaused.value = false
        runCurrent()
        assertEquals(before, engines.log)
    }

    @Test
    fun `a one-stream channel the server cannot keep goes back to the provider, still paused`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 1)
        host.refuse = true
        c.tune(channel(31))
        runCurrent()
        engines.exoUserPaused.value = true
        runCurrent()
        advanceTimeBy(host.firstPieceMs + 1)
        assertEquals("exo:${channel(31).streamUrl}", engines.log[engines.log.size - 2])
        assertEquals("exo-pause", engines.log.last())
        assertNull(c.localTimeshift.value)
    }

    @Test
    fun `an unknown connection limit counts as one`() = runTest {
        val (c, engines, host) = setUp(maxConnections = 0)
        c.preview(channel(40), muted = true)
        advanceTimeBy(LiveTuneController.COPY_SETTLE_MS + host.firstPieceMs + 1)
        assertEquals(listOf("exo:${channel(40).streamUrl}"), engines.log)
        assertTrue(host.asked.isEmpty())
        assertFalse(engines.log.any { TimeshiftServer.isLocal(it.removePrefix("exo:")) })
    }

    private companion object {
        const val WALL_BASE = 1_700_000_000_000L
    }
}
