package tv.own.owntv.core.recording

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.model.RecordingStatus

/** The pure half of the DPTV-Recorder client: markers, statuses, addresses and the server's JSON. */
class RemoteRecordingsTest {

    private fun row(filePath: String?) = RecordingEntity(
        profileId = 1, sourceId = 1, channelId = 1, channelName = "Fox League", streamUrl = "http://x/1.ts",
        title = "NRL", programmeStartMs = 0, programmeStopMs = 1, startMs = 0, stopMs = 1, filePath = filePath,
    )

    @Test
    fun `marker round-trips and only marks server rows`() {
        val marker = RemoteRecordings.markerFor(42)
        assertEquals("dptv-recorder:42", marker)
        assertEquals(42L, RemoteRecordings.remoteIdOf(marker))
        assertTrue(RemoteRecordings.isRemote(row(marker)))
        assertFalse(RemoteRecordings.isRemote(row("/storage/emulated/0/OwnTV/TV/NRL.ts")))
        assertFalse(RemoteRecordings.isRemote(row("content://com.android.externalstorage/tree/x")))
        assertFalse(RemoteRecordings.isRemote(row(null)))
        assertNull(RemoteRecordings.remoteIdOf("dptv-recorder:abc"))
    }

    @Test
    fun `server statuses map to the app's and unknown is failed`() {
        assertEquals(RecordingStatus.SCHEDULED, RemoteRecordings.statusOf("scheduled"))
        assertEquals(RecordingStatus.RECORDING, RemoteRecordings.statusOf("recording"))
        assertEquals(RecordingStatus.COMPLETED, RemoteRecordings.statusOf("Completed"))
        assertEquals(RecordingStatus.CANCELLED, RemoteRecordings.statusOf("cancelled"))
        assertEquals(RecordingStatus.FAILED, RemoteRecordings.statusOf("failed"))
        assertEquals(RecordingStatus.FAILED, RemoteRecordings.statusOf("something new"))
    }

    @Test
    fun `addresses are normalised`() {
        assertEquals("http://192.168.1.10:3000", RemoteRecordings.normalizeBaseUrl(" http://192.168.1.10:3000/ "))
        assertEquals("http://nas:3000", RemoteRecordings.normalizeBaseUrl("nas:3000"))
        assertEquals("https://rec.example.com", RemoteRecordings.normalizeBaseUrl("https://rec.example.com"))
        assertEquals("http://nas:3000/recorder", RemoteRecordings.normalizeBaseUrl("http://nas:3000/recorder/"))
        assertNull(RemoteRecordings.normalizeBaseUrl("   "))
        assertNull(RemoteRecordings.normalizeBaseUrl("http://"))
    }

    @Test
    fun `parses the server's recording rows`() {
        val body = """
            [
              {"id":7,"user_id":1,"channel_id":"4321","channel_name":"Fox League","program_title":"NRL Grand Final",
               "program_start":1000,"program_stop":2000,"rec_start":900,"rec_end":2300,"status":"completed",
               "filename":"NRL.ts","error":null,"created_at":1,"kind":"catchup"},
              {"id":8,"channel_id":"99","channel_name":"Sky","program_title":"News","program_start":3000,
               "program_stop":4000,"rec_start":2900,"rec_end":4300,"status":"failed","error":"ffmpeg exited with code 1"},
              {"no_id":true}
            ]
        """.trimIndent()
        val rows = RemoteRecordings.parseList(body)
        assertEquals(2, rows.size)
        val first = rows[0]
        assertEquals(7L, first.id)
        assertEquals("4321", first.channelId)
        assertEquals("NRL Grand Final", first.title)
        assertEquals(1000L, first.programmeStartMs)
        assertEquals(2300L, first.stopMs)
        assertEquals("catchup", first.kind)
        assertNull(first.error)
        // A server from before catch-up sends no kind: it is a live recording.
        assertEquals("live", rows[1].kind)
        assertEquals("ffmpeg exited with code 1", rows[1].error)
    }

    @Test
    fun `failures carry the server's reason`() {
        val unauthorized = RemoteRecordings.failure(401, """{"error":"Invalid API key."}""")
        assertEquals(RemoteRecorderException.Kind.UNAUTHORIZED, unauthorized.kind)
        val refused = RemoteRecordings.failure(400, """{"error":"This program has already ended."}""")
        assertEquals(RemoteRecorderException.Kind.REFUSED, refused.kind)
        assertEquals("This program has already ended.", refused.detail)
        val bare = RemoteRecordings.failure(502, "<html>Bad Gateway</html>")
        assertEquals("502", bare.detail)
    }

    @Test
    fun `a single created row parses`() {
        val created = RemoteRecordings.parse(JSONObject("""{"id":3,"channel_id":"1","status":"scheduled"}"""))
        assertEquals(3L, created?.id)
        assertEquals("scheduled", created?.status)
    }
}
