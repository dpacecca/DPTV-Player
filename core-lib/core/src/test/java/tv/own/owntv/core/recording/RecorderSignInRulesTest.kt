package tv.own.owntv.core.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.own.owntv.core.recording.RecorderSignInRules.Origin
import tv.own.owntv.core.recording.RecorderSignInRules.Step

/** (DPTV v2.9.14) Signing in to DPTV-Recorder with the DPTV-Server login. */
class RecorderSignInRulesTest {
    private val rec = "http://nas:3000"
    private val me = RecorderSignInRules.signedInAs(rec, "alice", 7)

    @Test fun `nothing set signs in`() {
        assertEquals(Step.SIGN_IN, RecorderSignInRules.step(Origin("", "", "", false), rec, me))
    }

    @Test fun `a recorder set by hand is never touched`() {
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin("", "", "", true), rec, me))
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin("http://other", "dptv_x", "", true), rec, me))
    }

    @Test fun `settings from before this version are kept`() {
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin("http://other", "dptv_x", "", false), rec, me))
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin("", "dptv_x", "", false), rec, me))
    }

    @Test fun `no recorder advertised keeps what is there`() {
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin("", "", "", false), null, null))
        assertEquals(Step.KEEP, RecorderSignInRules.step(Origin(rec, "dptv_x", me, false), null, null))
    }

    @Test fun `signed in already is checked, a change signs in again`() {
        assertEquals(Step.CHECK, RecorderSignInRules.step(Origin(rec, "dptv_x", me, false), rec, me))
        val moved = RecorderSignInRules.signedInAs("http://new:3000", "alice", 7)
        assertEquals(Step.SIGN_IN, RecorderSignInRules.step(Origin(rec, "dptv_x", me, false), "http://new:3000", moved))
        val otherLogin = RecorderSignInRules.signedInAs(rec, "bob", 7)
        assertEquals(Step.SIGN_IN, RecorderSignInRules.step(Origin(rec, "dptv_x", me, false), rec, otherLogin))
        // The key was dropped (the recorder stopped taking it).
        assertEquals(Step.SIGN_IN, RecorderSignInRules.step(Origin(rec, "", me, false), rec, me))
    }

    @Test fun `reads the advertised recorder`() {
        assertEquals(rec, RecorderSignInRules.advertisedRecorder("""{"user_info":{"auth":1},"server_info":{"dptv_recorder_url":"http://nas:3000/"}}"""))
        assertEquals("http://nas:3000", RecorderSignInRules.advertisedRecorder("""{"server_info":{"dptv_recorder_url":"nas:3000"}}"""))
        assertNull(RecorderSignInRules.advertisedRecorder("""{"user_info":{"auth":1},"server_info":{}}"""))
        assertNull(RecorderSignInRules.advertisedRecorder("not json"))
    }

    @Test fun `reads the issued key`() {
        assertEquals("dptv_abc", RecorderSignInRules.issuedKey("""{"apiKey":"dptv_abc","username":"alice"}"""))
        assertNull(RecorderSignInRules.issuedKey("""{"error":"no"}"""))
        assertNull(RecorderSignInRules.issuedKey("<html>"))
    }

    @Test fun `device names tell two of one model apart`() {
        assertEquals("NVIDIA SHIELD (a1b2c3)", RecorderSignInRules.deviceName("NVIDIA SHIELD", "a1b2c3d4e5"))
        assertEquals("Android TV", RecorderSignInRules.deviceName(" ", ""))
    }
}
