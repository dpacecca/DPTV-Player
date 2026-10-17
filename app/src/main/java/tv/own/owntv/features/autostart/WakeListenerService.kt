package tv.own.owntv.features.autostart

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import tv.own.owntv.R

/**
 * Settings › App › Auto start on wake-up. Android has no manifest broadcast for "the screen came on",
 * so this listens for ACTION_SCREEN_ON itself, and only while that option is on.
 *
 * A foreground service, because a registered receiver in a cached process stops hearing the broadcast
 * (Android 14 defers it, and the process can be killed at any time); typed `specialUse`, because none
 * of the other types describes waiting for a wake-up, and `specialUse` may start from BOOT_COMPLETED.
 * It runs in the bare `:autostart` process (see [AutoStart.isAutoStartProcess]), holds no wake lock and
 * does no work between wake-ups, so its cost is a few megabytes of memory and a silent, minimum-importance
 * notification. Without the notification permission the service still runs; only the notification
 * is hidden.
 */
class WakeListenerService : Service() {

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON) AutoStart.onScreenOn(context)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // A protected system broadcast: only the system can send it, which a not-exported receiver still hears.
        ContextCompat.registerReceiver(this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val started = runCatching {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), serviceType())
        }
        if (started.isFailure) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenOn) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun serviceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0

    private fun notification(): android.app.Notification {
        // The Application is wrapped in the chosen app language; this service's own context is not.
        val localized = applicationContext
        val title = localized.getString(R.string.auto_start_notification_channel)
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, title, NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) },
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.owntv_notification)
            .setContentTitle(title)
            .setContentText(localized.getString(R.string.auto_start_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "owntv_auto_start"
        const val NOTIFICATION_ID = 4210
    }
}
