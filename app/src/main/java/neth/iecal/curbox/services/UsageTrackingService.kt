package neth.iecal.curbox.services

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.net.toUri
import neth.iecal.curbox.R
import neth.iecal.curbox.anti_stimulants.MindfulMessage
import neth.iecal.curbox.trackers.AppUsageTracker
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.trackers.WebsiteUsageTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import neth.iecal.curbox.utils.AppLogger

class UsageTrackingService : BaseBlockingService() {

    private val TAG = "UsageTrackingService"
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val mindfulMessage = MindfulMessage()
    private val websiteUsageTracker = WebsiteUsageTracker()
    private val appUsageTracker = AppUsageTracker()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // No heavy logging here to prevent main-thread block
        super.onAccessibilityEvent(event)
        try {
            appUsageTracker.onEvent(event)
            reelsCountTracker.onEvent(event)
            mindfulMessage.onEvent(event)
            websiteUsageTracker.onEvent(event)
        } catch (error: Exception) {
            AppLogger.functionError(TAG, "onAccessibilityEvent", error)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        try {
            super.onServiceConnected()
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes =
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or 
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT or 
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or 
                        AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            
            reelsCountTracker.setup(this, reelsOverlayManager)
            mindfulMessage.setup(this)
            websiteUsageTracker.setup(this)
            appUsageTracker.setup(this)

            reelsCountTracker.setupReceivers()

            if (!Settings.canDrawOverlays(this)) {
                AppLogger.logWarn(TAG, "App cannot draw overlays - requesting permission")
                Toast.makeText(
                    this,
                    getString(R.string.please_provide_draw_over_other_apps),
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    "package:$packageName".toUri()
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                startActivity(intent)
            }
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onServiceConnected", e)
        }
    }

    override fun onInterrupt() {
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mindfulMessage.onDestroy()
            reelsCountTracker.onDestroy()
            websiteUsageTracker.onDestroy()
            appUsageTracker.onDestroy()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}
