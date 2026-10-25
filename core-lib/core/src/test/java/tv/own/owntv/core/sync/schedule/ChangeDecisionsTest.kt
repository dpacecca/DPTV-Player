package tv.own.owntv.core.sync.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The change check's decisions (v2.5.3): 304 / ETag / Last-Modified / hash, DPTV-Server, throttles. */
class ChangeDecisionsTest {

    private val dptvBody = """
        {"server":"DPTV-Server","version":"1.0.4",
         "live":{"rev":"l1","updated_at":1793001720},
         "vod":{"rev":"v1","updated_at":1792980000},
         "series":{"rev":"s1","updated_at":null},
         "epg":{"rev":"e1","updated_at":1793001600},
         "last_source_sync_at":1793001720,"last_epg_refresh_at":1793001600}
    """.trimIndent()

    // What an ordinary Xtream panel answers for an action it does not know: its usual account body.
    private val xcUserInfo = """
        {"user_info":{"username":"u","auth":1,"status":"Active","max_connections":"1"},
         "server_info":{"url":"example","port":"80","timezone":"Europe/Berlin"}}
    """.trimIndent()

    // --- one endpoint ---

    @Test
    fun a304IsUnchangedAndKeepsTheStoredValidators() {
        val stored = Validators(etag = "\"a\"")
        val d = ChangeDecisions.decide(stored, ProbeResult(code = 304))
        assertEquals(Verdict.UNCHANGED, d.verdict)
        assertEquals(ChangeSignal.ETAG, d.signal)
        assertEquals(stored, d.store)
        assertEquals(ChangeSignal.LAST_MODIFIED, ChangeDecisions.decide(Validators(lastModified = "x"), ProbeResult(304)).signal)
    }

    @Test
    fun anEtagIsABaselineFirstThenComparedEvenWithout304() {
        val first = ChangeDecisions.decide(null, ProbeResult(200, etag = "\"a\""))
        assertEquals(Verdict.BASELINE, first.verdict)
        assertEquals(ChangeSignal.ETAG, first.signal)
        // A server that sends validators but never answers 304: compared here.
        assertEquals(Verdict.UNCHANGED, ChangeDecisions.decide(first.store, ProbeResult(200, etag = "\"a\"")).verdict)
        val changed = ChangeDecisions.decide(first.store, ProbeResult(200, etag = "\"b\""))
        assertEquals(Verdict.CHANGED, changed.verdict)
        assertEquals("\"b\"", changed.store?.etag)
    }

    @Test
    fun lastModifiedIsUsedWhenThereIsNoEtag() {
        val stored = Validators(lastModified = "Sun, 25 Oct 2026 14:00:00 GMT")
        assertEquals(Verdict.UNCHANGED, ChangeDecisions.decide(stored, ProbeResult(200, lastModified = stored.lastModified)).verdict)
        val d = ChangeDecisions.decide(stored, ProbeResult(200, lastModified = "Sun, 25 Oct 2026 16:00:00 GMT"))
        assertEquals(Verdict.CHANGED, d.verdict)
        assertEquals(ChangeSignal.LAST_MODIFIED, d.signal)
    }

    @Test
    fun noValidatorsFallsBackToTheBodyHash() {
        val base = ChangeDecisions.decide(null, ProbeResult(200, bodyHash = "h1"))
        assertEquals(Verdict.BASELINE, base.verdict)
        assertEquals(ChangeSignal.CONTENT_HASH, base.signal)
        assertEquals(Verdict.UNCHANGED, ChangeDecisions.decide(base.store, ProbeResult(200, bodyHash = "h1")).verdict)
        assertEquals(Verdict.CHANGED, ChangeDecisions.decide(base.store, ProbeResult(200, bodyHash = "h2")).verdict)
        // A list too large to hash, with no validators, is no signal at all.
        assertEquals(Verdict.NO_SIGNAL, ChangeDecisions.decide(base.store, ProbeResult(200, contentLength = 99_000_000)).verdict)
    }

    @Test
    fun anErrorOrNoResponseFailsWithoutLosingWhatWasStored() {
        val stored = Validators(etag = "x")
        val d = ChangeDecisions.decide(stored, ProbeResult(401))
        assertEquals(Verdict.FAILED, d.verdict)
        assertEquals(ChangeSignal.FAILED, d.signal)
        assertEquals(stored, d.store)
        assertEquals(Verdict.FAILED, ChangeDecisions.decide(null, ProbeResult(0)).verdict)
    }

    @Test
    fun validatorsSurviveTheirJsonForm() {
        val v = Validators(etag = "\"e\"", lastModified = "m", hash = "h")
        assertEquals(v, Validators.fromJson(v.toJson()))
        assertEquals(Validators(), Validators.fromJson(Validators().toJson()))
    }

    // --- several endpoints ---

    @Test
    fun combiningEndpointsReportsAChangeIfAnyChanged() {
        fun d(v: Verdict, s: ChangeSignal = ChangeSignal.CONTENT_HASH) = ProbeDecision(v, s, null)
        assertEquals(Verdict.CHANGED, ChangeDecisions.combine(listOf(d(Verdict.UNCHANGED), d(Verdict.CHANGED))))
        assertEquals(Verdict.BASELINE, ChangeDecisions.combine(listOf(d(Verdict.UNCHANGED), d(Verdict.BASELINE))))
        assertEquals(Verdict.UNCHANGED, ChangeDecisions.combine(listOf(d(Verdict.UNCHANGED), d(Verdict.FAILED, ChangeSignal.FAILED))))
        assertEquals(Verdict.FAILED, ChangeDecisions.combine(listOf(d(Verdict.FAILED, ChangeSignal.FAILED))))
        assertEquals(Verdict.NO_SIGNAL, ChangeDecisions.combine(emptyList()))
        // The status line names the best signal any endpoint gave.
        assertEquals(
            ChangeSignal.ETAG,
            ChangeDecisions.strongest(listOf(d(Verdict.UNCHANGED, ChangeSignal.CONTENT_HASH), d(Verdict.UNCHANGED, ChangeSignal.ETAG))),
        )
        assertEquals(ChangeSignal.FAILED, ChangeDecisions.strongest(listOf(d(Verdict.FAILED, ChangeSignal.FAILED))))
    }

    // --- DPTV-Server ---

    @Test
    fun theDptvStatusParses() {
        val s = ChangeDecisions.parseDptvStatus(dptvBody)
        assertNotNull(s)
        s!!
        assertEquals("1.0.4", s.version)
        assertEquals("l1", s.live?.rev)
        assertEquals(1793001720L, s.live?.updatedAt)
        assertNull(s.series?.updatedAt)
        assertEquals(1793001720L, s.lastSourceSyncAt)
        assertEquals(1793001600L, s.lastEpgRefreshAt)
        assertEquals(mapOf("live" to "l1", "vod" to "v1", "series" to "s1", "epg" to "e1"), s.revs())
    }

    @Test
    fun anOrdinaryPanelOrGarbageIsNotDptv() {
        assertNull(ChangeDecisions.parseDptvStatus(xcUserInfo))
        assertNull(ChangeDecisions.parseDptvStatus("<html>nope</html>"))
        assertNull(ChangeDecisions.parseDptvStatus(""))
        assertNull(ChangeDecisions.parseDptvStatus("""{"server":"Other","live":{"rev":"x"}}"""))
        // Nulls the server is allowed to send.
        val sparse = ChangeDecisions.parseDptvStatus("""{"server":"DPTV-Server","live":{"rev":null,"updated_at":null},"last_source_sync_at":null}""")
        assertNotNull(sparse)
        assertNull(sparse!!.live?.rev)
        assertNull(sparse.lastSourceSyncAt)
        assertEquals(emptyMap<String, String>(), sparse.revs())
    }

    @Test
    fun eachChangedRevisionAsksForItsOwnSync() {
        val now = ChangeDecisions.parseDptvStatus(dptvBody)!!
        // First answer: a baseline, nothing starts.
        assertEquals(emptySet<ScheduledKind>(), ChangeDecisions.dptvTriggers(null, now))
        assertEquals(emptySet<ScheduledKind>(), ChangeDecisions.dptvTriggers(now.revs(), now))
        val old = mapOf("live" to "l0", "vod" to "v1", "series" to "s1", "epg" to "e1")
        assertEquals(setOf(ScheduledKind.LIVE), ChangeDecisions.dptvTriggers(old, now))
        assertEquals(setOf(ScheduledKind.VOD), ChangeDecisions.dptvTriggers(old + ("live" to "l1") + ("series" to "s0"), now))
        assertEquals(setOf(ScheduledKind.VOD), ChangeDecisions.dptvTriggers(old + ("live" to "l1") + ("vod" to "v0"), now))
        assertEquals(setOf(ScheduledKind.EPG), ChangeDecisions.dptvTriggers(old + ("live" to "l1") + ("epg" to "e0"), now))
        assertEquals(
            setOf(ScheduledKind.LIVE, ScheduledKind.VOD, ScheduledKind.EPG),
            ChangeDecisions.dptvTriggers(mapOf("live" to "a", "vod" to "b", "series" to "s1", "epg" to "c"), now),
        )
        // A type the server stopped reporting is not a change.
        assertEquals(emptySet<ScheduledKind>(), ChangeDecisions.dptvTriggers(now.revs() + ("extra" to "x"), now))
    }

    @Test
    fun theDetectionIsTrustedForADay() {
        val now = 10_000_000_000L
        assertFalse(ChangeDecisions.detectionFresh(null, now))
        assertTrue(ChangeDecisions.detectionFresh(now - 23 * 3_600_000L, now))
        assertFalse(ChangeDecisions.detectionFresh(now - 24 * 3_600_000L, now))
        // A clock set backwards does not pin a stale answer forever.
        assertFalse(ChangeDecisions.detectionFresh(now + 60_000L, now))
    }

    // --- throttles ---

    @Test
    fun filmsAndShowsChangesSyncAtMostEverySixHoursLiveAndGuideHourly() {
        val now = 50_000_000_000L
        assertTrue(ChangeDecisions.changeSyncAllowed(ScheduledKind.VOD, null, now))
        assertFalse(ChangeDecisions.changeSyncAllowed(ScheduledKind.VOD, now - 5 * 3_600_000L, now))
        assertTrue(ChangeDecisions.changeSyncAllowed(ScheduledKind.VOD, now - 6 * 3_600_000L, now))
        assertFalse(ChangeDecisions.changeSyncAllowed(ScheduledKind.LIVE, now - 30 * 60_000L, now))
        assertTrue(ChangeDecisions.changeSyncAllowed(ScheduledKind.LIVE, now - 60 * 60_000L, now))
        assertTrue(ChangeDecisions.changeSyncAllowed(ScheduledKind.EPG, now - 61 * 60_000L, now))
        // A clock set backwards never blocks.
        assertTrue(ChangeDecisions.changeSyncAllowed(ScheduledKind.VOD, now + 60_000L, now))
    }
}
