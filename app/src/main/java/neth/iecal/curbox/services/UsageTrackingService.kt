package neth.iecal.curbox.services

import neth.iecal.curbox.R

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import neth.iecal.curbox.trackers.ReelsCountTracker
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import androidx.core.net.toUri
import neth.iecal.curbox.trackers.MindfulMessageTracker
import neth.iecal.curbox.utils.AppLogger

class UsageTrackingService : BaseBlockingService() {

    private val TAG = "UsageTrackingService"
    private val reelsOverlayManager by lazy { ReelsOverlayManager(this) }
    private val reelsCountTracker = ReelsCountTracker()
    private val mindfulMessageTracker = MindfulMessageTracker()

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        // No heavy logging here to prevent main-thread block
        super.onAccessibilityEvent(event)
        try {
            reelsCountTracker.onEvent(event)
            mindfulMessageTracker.onEvent(event)
        } catch (error: Exception) {
            AppLogger.functionError(TAG, "onAccessibilityEvent", error)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        try {
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes =
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT
            }
            
            reelsCountTracker.setup(this, reelsOverlayManager)
            mindfulMessageTracker.setup(this)
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
        try {
            super.onDestroy()
            mindfulMessageTracker.onDestroy()
            reelsCountTracker.onDestroy()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}
