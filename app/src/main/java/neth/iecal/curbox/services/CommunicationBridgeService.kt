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
 * Background communication service that acts as a bridge between the main app UI
 * and the accessibility services. This service persists even when the main app is closed
 * to ensure stable communication with accessibility services.
 */
class CommunicationBridgeService : Service() {

    private val TAG = "CommunicationBridgeService"
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "communication_bridge_channel"

    private var lastAccessibilityHeartbeat: Long = 0
    private val heartbeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeatPing()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL)
        }
    }

    private val communicationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            try {
                when (action) {
                    // Forward broadcasts to accessibility services
                    in REFRESH_ACTIONS -> {
                        // Re-broadcast to accessibility services
                        sendBroadcast(intent)
                    }

                    // Handle service status requests
                    ACTION_CHECK_STATUS -> {
                        sendServiceStatusBroadcast()
                    }

                    // Handle heartbeat pong
                    ACTION_PONG -> {
                        lastAccessibilityHeartbeat = System.currentTimeMillis()
                    }
                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "communicationReceiver.onReceive", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        // Starting from Android 14 (API 34), we must specify the foreground service type
        // and ensure we have the FOREGROUND_SERVICE permission.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
            // If it fails to start as foreground, stop the service to avoid background execution limits
            stopSelf()
            return
        }

        registerCommunicationReceiver()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Send initial status broadcast
        sendServiceStatusBroadcast()

        // Return START_STICKY to ensure service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        stopHeartbeat()
        try {
            unregisterReceiver(communicationReceiver)
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }

        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Communication Bridge",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Maintains communication with accessibility services"
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
            .setContentText("You can dismiss this notification. Curbox will continue to run")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun registerCommunicationReceiver() {
        val filter = IntentFilter().apply {
            REFRESH_ACTIONS.forEach { addAction(it) }
            addAction(ACTION_CHECK_STATUS)
            addAction(ACTION_PONG)
        }

        ContextCompat.registerReceiver(
            this,
            communicationReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    private fun startHeartbeat() {
        heartbeatHandler.post(heartbeatRunnable)
    }

    private fun stopHeartbeat() {
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun sendHeartbeatPing() {
        val pingIntent = Intent(ACTION_PING)
        sendBroadcast(pingIntent)
    }

    private fun sendServiceStatusBroadcast() {
        val isAccessibilityAlive = (System.currentTimeMillis() - lastAccessibilityHeartbeat) < HEARTBEAT_TIMEOUT
        val statusIntent = Intent(ACTION_STATUS_RESPONSE).apply {
            putExtra("bridge_service_running", true)
            putExtra("accessibility_service_alive", isAccessibilityAlive)
            putExtra("timestamp", System.currentTimeMillis())
        }
        sendBroadcast(statusIntent)
    }

    companion object {
        const val ACTION_START_BRIDGE = "neth.iecal.curbox.ACTION_START_BRIDGE"
        const val ACTION_STOP_BRIDGE = "neth.iecal.curbox.ACTION_STOP_BRIDGE"
        const val ACTION_CHECK_STATUS = "neth.iecal.curbox.ACTION_CHECK_SERVICE_STATUS"
        const val ACTION_STATUS_RESPONSE = "neth.iecal.curbox.ACTION_SERVICE_STATUS"
        const val ACTION_PING = "neth.iecal.curbox.ACTION_PING"
        const val ACTION_PONG = "neth.iecal.curbox.ACTION_PONG"

        private const val HEARTBEAT_INTERVAL = 15000L // 15 seconds
        private const val HEARTBEAT_TIMEOUT = 45000L  // 45 seconds

        // Refresh Actions
        const val ACTION_REFRESH_APPBLOCKER = "neth.iecal.curbox.refresh.appblocker"
        const val ACTION_REFRESH_APPBLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"
        const val ACTION_REFRESH_REELBLOCKER = "neth.iecal.curbox.refresh.reelblocker"
        const val ACTION_REFRESH_REELBLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.reelblocker.cooldown"
        const val ACTION_REFRESH_GRAYSCALE = "neth.iecal.curbox.refresh.grayscale"
        const val ACTION_REFRESH_KEYWORD_CONFIG = "neth.iecal.curbox.refresh.keywordblocker.config"
        const val ACTION_REFRESH_VIEWBLOCKER = "neth.iecal.curbox.refresh.viewblocker"
        const val ACTION_REFRESH_FOCUS_MODE = "neth.iecal.curbox.refresh.focus_mode"
        const val ACTION_EXIT_AUTO_FOCUS = "neth.iecal.curbox.exit.auto_focus"
        const val ACTION_UNSUSPEND_ALL = "neth.iecal.curbox.unsuspend_all_apps"

        val REFRESH_ACTIONS = listOf(
            ACTION_REFRESH_APPBLOCKER,
            ACTION_REFRESH_APPBLOCKER_COOLDOWN,
            ACTION_REFRESH_REELBLOCKER,
            ACTION_REFRESH_REELBLOCKER_COOLDOWN,
            ACTION_REFRESH_GRAYSCALE,
            ACTION_REFRESH_KEYWORD_CONFIG,
            ACTION_REFRESH_VIEWBLOCKER,
            ACTION_REFRESH_FOCUS_MODE,
            ACTION_EXIT_AUTO_FOCUS,
            ACTION_UNSUSPEND_ALL
        )

        /**
         * Starts the communication bridge service
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
         * Stops the communication bridge service
         */
        fun stopService(context: Context) {
            val intent = Intent(context, CommunicationBridgeService::class.java)
            context.stopService(intent)
        }

        /**
         * Checks if the bridge service is running
         */
        fun checkServiceStatus(context: Context) {
            val intent = Intent(ACTION_CHECK_STATUS)
            context.sendBroadcast(intent)
        }
    }
}