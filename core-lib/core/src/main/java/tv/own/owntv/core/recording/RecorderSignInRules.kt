package tv.own.owntv.core.recording

import org.json.JSONObject

/**
 * (DPTV v2.9.14) Signing in to DPTV-Recorder with this device's DPTV-Server login.
 *
 * DPTV-Server tells a signed-in player its recorder's address (`server_info.dptv_recorder_url`).
 * With no recorder set on this device, the player signs in there with the playlist's login
 * (`POST /api/auth/dptv`) and keeps the key the recorder issues for this device. A recorder set in
 * Settings by hand always wins: once anything is typed there, this never runs again.
 */
object RecorderSignInRules {

    /** Where this device's recorder settings came from. */
    data class Origin(val url: String, val key: String, val signedInAs: String, val setByHand: Boolean)

    /** Which recorder, login and playlist a key came from; a change means signing in again. */
    fun signedInAs(recorderUrl: String, username: String, sourceId: Long): String = "$sourceId|$username|$recorderUrl"

    enum class Step {
        /** Leave the recorder settings alone. */
        KEEP,

        /** Sign in and store what the recorder issues. */
        SIGN_IN,

        /** Signed in this way already: make sure the recorder still takes the key. */
        CHECK,
    }

    /**
     * What to do, given the settings, the recorder DPTV-Server advertises (normalised, or null) and
     * which login/playlist that answer came for ([signedInAs]).
     */
    fun step(origin: Origin, advertised: String?, signedInAs: String?): Step {
        if (origin.setByHand) return Step.KEEP
        if (advertised == null || signedInAs == null) return Step.KEEP
        if (origin.signedInAs.isEmpty()) {
            // Set before this version (by hand, or restored): theirs.
            return if (origin.url.isBlank() && origin.key.isBlank()) Step.SIGN_IN else Step.KEEP
        }
        return if (origin.signedInAs == signedInAs && origin.key.isNotBlank()) Step.CHECK else Step.SIGN_IN
    }

    /** The recorder DPTV-Server advertises in its `player_api.php` answer, normalised; null when none. */
    fun advertisedRecorder(playerApiAnswer: String): String? = runCatching {
        val info = JSONObject(playerApiAnswer).optJSONObject("server_info") ?: return null
        RemoteRecordings.normalizeBaseUrl(info.optString("dptv_recorder_url", ""))
    }.getOrNull()

    fun requestBody(username: String, password: String, device: String): String =
        JSONObject().put("username", username).put("password", password).put("device", device).toString()

    /** The key from the recorder's answer; null when it isn't one. */
    fun issuedKey(answer: String): String? = runCatching {
        JSONObject(answer).optString("apiKey", "").trim().takeIf { it.startsWith("dptv_") }
    }.getOrNull()

    /**
     * The key's name on the recorder. One key per device: signing in again replaces it there, so two
     * TVs of the same model get the start of their own device ID to keep them apart.
     */
    fun deviceName(model: String, deviceId: String): String =
        listOf(model.trim().ifEmpty { "Android TV" }, deviceId.take(6).takeIf { it.isNotEmpty() }?.let { "($it)" })
            .filterNotNull().joinToString(" ")
}
