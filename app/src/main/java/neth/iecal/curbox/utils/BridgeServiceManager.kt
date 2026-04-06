package neth.iecal.curbox.utils

import android.content.Context
import android.content.Intent
import neth.iecal.curbox.services.CommunicationBridgeService

/**
 * Utility class to manage the background service lifecycle.
 * Ensures the service is running to keep the process alive.
 */
object BridgeServiceManager {

    private val TAG = "BridgeServiceManager"
    private var isBridgeRunning = false

    /**
     * Ensures the background service is running.
     */
    fun ensureBridgeRunning(context: Context) {
        try {
            if (!isBridgeRunning) {
                CommunicationBridgeService.startService(context)
                isBridgeRunning = true
            }
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "ensureBridgeRunning", e)
        }
    }

    /**
     * Stops the background service.
     */
    fun stopBridgeService(context: Context) {
        try {
            CommunicationBridgeService.stopService(context)
            isBridgeRunning = false
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "stopBridgeService", e)
        }
    }

    /**
     * Sends a broadcast and ensures the service is running.
     */
    fun sendBridgedBroadcast(context: Context, intent: Intent) {
        ensureBridgeRunning(context)
        context.sendBroadcast(intent)
    }

    /**
     * Legacy check for bridge service status
     */
    fun isBridgeServiceRunning(): Boolean {
        return isBridgeRunning
    }

    /**
     * Cleans up resources
     */
    fun cleanup(context: Context) {
        // No-op in simplified version
    }
}