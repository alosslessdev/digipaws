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
        AppLogger.logDebug(TAG, "Initializing DataStoreManager")
        DataStoreManager(this)
    }


    var lastBackPressTimeStamp: Long =
        SystemClock.uptimeMillis() // prevents repetitive global actions

    override fun onServiceConnected() {
        AppLogger.logServiceLifecycle(TAG, "onServiceConnected()", "Service connected to accessibility framework")
        super.onServiceConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        AppLogger.functionEntry(TAG, "onAccessibilityEvent", "event=${event?.eventType}")
    }

    override fun onDestroy() {
        AppLogger.logServiceLifecycle(TAG, "onDestroy()", "Service is being destroyed")
        super.onDestroy()
    }

    override fun onInterrupt() {
        AppLogger.logServiceLifecycle(TAG, "onInterrupt()", "Accessibility service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        AppLogger.logServiceLifecycle(TAG, "onUnbind()", "Unbinding from accessibility service")
        return super.onUnbind(intent)
    }


    fun isDelayOver(lastTimestamp: Long, delay: Int): Boolean {
        AppLogger.functionEntry(TAG, "isDelayOver", "lastTimestamp=$lastTimestamp, delay=$delay")
        val currentTime = SystemClock.uptimeMillis().toFloat()
        val result = currentTime - lastTimestamp > delay
        AppLogger.logVariable(TAG, "isDelayOver_result", result)
        return result
    }

    fun pressHome() {
        AppLogger.functionEntry(TAG, "pressHome")
        try {
            performGlobalAction(GLOBAL_ACTION_HOME)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()
            AppLogger.logBlockerAction(TAG, "Navigation", "Press Home", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "pressHome", e)
        }
    }

    fun pressBack() {
        AppLogger.functionEntry(TAG, "pressBack")
        try {
            performGlobalAction(GLOBAL_ACTION_BACK)
            lastBackPressTimeStamp = SystemClock.uptimeMillis()
            AppLogger.logBlockerAction(TAG, "Navigation", "Press Back", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "pressBack", e)
        }
    }
}
