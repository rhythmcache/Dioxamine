package io.github.rhythmcache.dioxamine.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.rhythmcache.dioxamine.MainActivity
import io.github.rhythmcache.dioxamine.R

class DioxForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START

        when (action) {
            ACTION_STOP -> {
                handleStop()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                handleStart()
                return START_STICKY
            }
            else -> {
                handleStart()
                return START_STICKY
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseWakeLock()
        isRunning = false
        super.onDestroy()
    }

    private fun handleStart() {
        isRunning = true
        val notification = buildNotification()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AppLogger.i("DioxForegroundService", "startForeground succeeded with type dataSync")
        }.onFailure { e ->
            AppLogger.e("DioxForegroundService", "Failed to start foreground service", e)
            isRunning = false
            return
        }

        acquireWakeLock()
    }

    private fun handleStop() {
        releaseWakeLock()
        isRunning = false

        // Update preference if stopped directly via notification action
        getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("keep_alive_enabled", false)
            .apply()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun acquireWakeLock() {
        if (wakeLock == null || !wakeLock!!.isHeld) {
            runCatching {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "Dioxamine:KeepAliveWakeLock"
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                AppLogger.i("DioxForegroundService", "Acquired partial wake lock for Keep Alive")
            }.onFailure { e ->
                AppLogger.e("DioxForegroundService", "Failed to acquire wake lock", e)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                AppLogger.i("DioxForegroundService", "Released partial wake lock")
            }
            wakeLock = null
        }.onFailure { e ->
            AppLogger.e("DioxForegroundService", "Error releasing wake lock", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_keep_alive_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_keep_alive_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return Companion.buildNotification(this, adbDeviceCount, fastbootDeviceCount)
    }

    companion object {
        const val CHANNEL_ID = "dioxamine_keep_alive_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "io.github.rhythmcache.dioxamine.action.START_KEEP_ALIVE"
        const val ACTION_STOP = "io.github.rhythmcache.dioxamine.action.STOP_KEEP_ALIVE"
        const val ACTION_UPDATE = "io.github.rhythmcache.dioxamine.action.UPDATE_DEVICES"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var adbDeviceCount: Int = 0
            private set

        @Volatile
        var fastbootDeviceCount: Int = 0
            private set

        fun buildNotification(
            context: Context,
            adbCount: Int = adbDeviceCount,
            fastbootCount: Int = fastbootDeviceCount
        ): Notification {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val contentPendingIntent = PendingIntent.getActivity(
                context,
                0,
                launchIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val stopIntent = Intent(context, DioxForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            val stopPendingIntent = PendingIntent.getService(
                context,
                1,
                stopIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            val contentText = when {
                adbCount > 0 && fastbootCount > 0 -> {
                    val adbText = context.resources.getQuantityString(R.plurals.notification_adb_devices, adbCount, adbCount)
                    val fbText = context.resources.getQuantityString(R.plurals.notification_fastboot_devices, fastbootCount, fastbootCount)
                    "$adbText, $fbText"
                }
                adbCount > 0 -> {
                    context.resources.getQuantityString(R.plurals.notification_adb_devices, adbCount, adbCount)
                }
                fastbootCount > 0 -> {
                    context.resources.getQuantityString(R.plurals.notification_fastboot_devices, fastbootCount, fastbootCount)
                }
                else -> {
                    context.getString(R.string.notification_keep_alive_no_devices)
                }
            }

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.notification_keep_alive_title))
                .setContentText(contentText)
                .setContentIntent(contentPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    context.getString(R.string.notification_keep_alive_stop_action),
                    stopPendingIntent
                )
                .build()
        }

        fun updateDeviceCounts(context: Context, adbCount: Int, fastbootCount: Int) {
            if (adbDeviceCount == adbCount && fastbootDeviceCount == fastbootCount) return
            adbDeviceCount = adbCount
            fastbootDeviceCount = fastbootCount
            if (isRunning) {
                runCatching {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    manager?.notify(NOTIFICATION_ID, buildNotification(context, adbCount, fastbootCount))
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, DioxForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DioxForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
