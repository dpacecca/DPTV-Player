package tv.own.owntv.core.sync.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Re-arms the fixed sync times when the clock or the time zone changes (v2.5.3): an alarm's delay
 * was worked out in the old zone, so without this the next run would land at the old local time.
 */
class SyncTimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED && intent.action != Intent.ACTION_TIME_CHANGED) return
        val scheduler = runCatching { GlobalContext.get().get<SyncScheduler>() }.getOrNull() ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                scheduler.armAll()
                Log.i("SyncScheduler", "re-armed after ${intent.action}")
            } catch (e: Exception) {
                Log.w("SyncScheduler", "re-arm after ${intent.action} failed", e)
            } finally {
                pending.finish()
            }
        }
    }
}
