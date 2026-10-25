package tv.own.owntv.core.sync.schedule

import android.util.Log
import tv.own.owntv.core.network.HttpClient

/**
 * Where the change check and the fixed sync times write their "sync-check:" lines (v2.5.3). The
 * diagnostics log lives in player-core, which core cannot see, so the app plugs it in at startup
 * (as it does for `CrashRecorder.diagnostics`). Every line is redacted here as a second guard.
 */
object SyncCheckLog {
    @Volatile var sink: ((String) -> Unit)? = null

    fun event(message: String) {
        val safe = HttpClient.redactUrl(message)
        Log.i("SyncCheck", safe)
        runCatching { sink?.invoke(safe) }
    }
}
