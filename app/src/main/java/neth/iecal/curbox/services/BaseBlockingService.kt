package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager
import kotlin.lazy

@SuppressLint("AccessibilityPolicy")
open class BaseBlockingService : AccessibilityService() {

    val dataStoreManager  by lazy {
        DataStoreManager(this)
    }


    var lastBackPressTimeStamp: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        startForegroundService()
        
        // Keep in-memory timestamp synced with MultiProcess DataStore
        CoroutineScope(Dispatchers.IO).launch {
            dataStoreManager.settings.collectLatest { settings ->
                lastBackPressTimeStamp = settings.lastBackPressTimeStamp
            }
        }
    }

    private fun startForegroundService() {
        val channelId = "blocking_service_channel"
        val channelName = getString(R.string.blocking_service_channel_name)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(
            channelId,
            channelName,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.blocking_service_channel_description)
        }
        notificationManager.createNotificationChannel(channel)

        val className = this::class.simpleName
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.blocking_service_notification_title, className))
            .setContentText(getString(R.string.blocking_service_notification_text))
            .setSmallIcon(R.drawable.icon)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOngoing(true)
            .build()

        val notificationId = this.javaClass.simpleName.hashCode()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(notificationId, notification)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onInterrupt() {
    }


    fun isDelayOver( delay: Int): Boolean {
        val currentTime = System.currentTimeMillis()
        return currentTime - lastBackPressTimeStamp > delay
    }

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        val now = System.currentTimeMillis()
        lastBackPressTimeStamp = now
        CoroutineScope(Dispatchers.IO).launch {
            dataStoreManager.updateLastBlockTimestamp(now)
        }
    }

    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        val now = System.currentTimeMillis()
        lastBackPressTimeStamp = now
        CoroutineScope(Dispatchers.IO).launch {
            dataStoreManager.updateLastBlockTimestamp(now)
        }
    }
}
