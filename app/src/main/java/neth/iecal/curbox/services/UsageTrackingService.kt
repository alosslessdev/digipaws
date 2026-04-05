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
    private val reelsOverlayManager by lazy { 
        AppLogger.logDebug(TAG, "Initializing ReelsOverlayManager")
        ReelsOverlayManager(this) 
    }
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
        AppLogger.logServiceLifecycle(TAG, "onServiceConnected()", "Service connected to accessibility framework")
        try {
            AppLogger.logDebug(TAG, "Setting up AccessibilityServiceInfo")
            serviceInfo = AccessibilityServiceInfo().apply {
                eventTypes =
                    AccessibilityEvent.TYPE_VIEW_SCROLLED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
                flags = AccessibilityServiceInfo.DEFAULT
            }
            AppLogger.logVariable(TAG, "serviceInfo.eventTypes", serviceInfo.eventTypes)
            
            AppLogger.functionEntry(TAG, "reelsCountTracker.setup")
            reelsCountTracker.setup(this, reelsOverlayManager)
            AppLogger.functionExit(TAG, "reelsCountTracker.setup")
            
            AppLogger.functionEntry(TAG, "mindfulMessageTracker.setup")
            mindfulMessageTracker.setup(this)
            AppLogger.functionExit(TAG, "mindfulMessageTracker.setup")
            
            AppLogger.functionEntry(TAG, "reelsCountTracker.setupReceivers")
            reelsCountTracker.setupReceivers()
            AppLogger.functionExit(TAG, "reelsCountTracker.setupReceivers")
            
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
                AppLogger.logDebug(TAG, "Started overlay permission activity")
            } else {
                AppLogger.logDebug(TAG, "App has permission to draw overlays")
            }
            
            AppLogger.logServiceLifecycle(TAG, "onServiceConnected COMPLETE", "All trackers setup successfully")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onServiceConnected", e)
        }
    }


    override fun onInterrupt() {
        AppLogger.logServiceLifecycle(TAG, "onInterrupt()", "Usage tracking service interrupted")
    }

    override fun onDestroy() {
        AppLogger.logServiceLifecycle(TAG, "onDestroy()", "UsageTrackingService being destroyed")
        try {
            super.onDestroy()
            AppLogger.functionEntry(TAG, "mindfulMessageTracker.onDestroy")
            mindfulMessageTracker.onDestroy()
            AppLogger.functionExit(TAG, "mindfulMessageTracker.onDestroy")
            
            AppLogger.functionEntry(TAG, "reelsCountTracker.onDestroy")
            reelsCountTracker.onDestroy()
            AppLogger.functionExit(TAG, "reelsCountTracker.onDestroy")
            
            AppLogger.logServiceLifecycle(TAG, "onDestroy COMPLETE", "All trackers cleaned up")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}
