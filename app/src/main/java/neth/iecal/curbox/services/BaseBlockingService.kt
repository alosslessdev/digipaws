package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.trackers.MindfulMessageTracker
import kotlin.lazy

open class BaseBlockingService : AccessibilityService() {

    private val TAG = this::class.simpleName ?: "BaseBlockingService"
    
    val dataStoreManager  by lazy {
        DataStoreManager(this)
    }


    var lastBackPressTimeStamp: Long =
        SystemClock.uptimeMillis() // prevents repetitive global actions

    override fun onServiceConnected() {
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onInterrupt() {
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        return super.onUnbind(intent)
    }


    fun isDelayOver(lastTimestamp: Long, delay: Int): Boolean {
        val currentTime = SystemClock.uptimeMillis().toFloat()
        return currentTime - lastTimestamp > delay
    }

    fun pressHome() {
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "pressHome", e)
        }
    }

    fun pressBack() {
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "pressBack", e)
        }
    }
}
