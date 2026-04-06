package neth.iecal.curbox.services

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.AppLogger
import android.Manifest

/**
 * Persistent foreground service that keeps the accessibility process alive even when
 * the main app UI is closed. It does not proxy broadcasts; callers send those directly.
 */
class CommunicationBridgeService : Service() {

    private val TAG = "CommunicationBridgeService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "communication_bridge_channel"

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Starting from Android 14 (API 34), we must specify the foreground service type
        // and ensure we have the FOREGROUND_SERVICE permission.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "startForeground", e)
            stopSelf()
            return
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Curbox Background Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ensures Curbox remains active in the background"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val notificationIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Curbox is Active")
            .setContentText("Curbox is running in the background to protect your focus")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        /**
         * Starts the persistent foreground service for the accessibility process.
         */
        fun startService(context: Context) {
            val intent = Intent(context, CommunicationBridgeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the persistent foreground service.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, CommunicationBridgeService::class.java)
            context.stopService(intent)
        }
    }
}
