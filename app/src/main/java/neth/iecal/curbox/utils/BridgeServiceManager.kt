package neth.iecal.curbox.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import neth.iecal.curbox.services.CommunicationBridgeService
import neth.iecal.curbox.utils.AppLogger

/**
 * Utility class to manage the Communication Bridge Service lifecycle.
 * Ensures the bridge service is running when needed and handles communication.
 */
object BridgeServiceManager {

    private val TAG = "BridgeServiceManager"
    private var isBridgeRunning = false
    private var lastStatusCheck = 0L

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CommunicationBridgeService.ACTION_STATUS_RESPONSE) {
                val running = intent.getBooleanExtra("bridge_service_running", false)

                isBridgeRunning = running
                lastStatusCheck = System.currentTimeMillis()
            }
        }
    }

    /**
     * Ensures the communication bridge service is running.
     * Call this when the app starts or when communication with accessibility services is needed.
     */
    fun ensureBridgeRunning(context: Context) {
        try {
            // Check if we need to start the service
            if (!isBridgeRunning || System.currentTimeMillis() - lastStatusCheck > 30000) { // 30 seconds
                CommunicationBridgeService.startService(context)
                isBridgeRunning = true
            }

            // Register status receiver if not already registered
            try {
                val filter = IntentFilter(CommunicationBridgeService.ACTION_STATUS_RESPONSE)
                    context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
            } catch (e: Exception) {
            }

            // Request status update
            CommunicationBridgeService.checkServiceStatus(context)

        } catch (e: Exception) {
            AppLogger.functionError(TAG, "ensureBridgeRunning", e)
        }
    }

    /**
     * Stops the communication bridge service.
     * Call this when the app is being destroyed or when accessibility services are disabled.
     */
    fun stopBridgeService(context: Context) {
        try {
            CommunicationBridgeService.stopService(context)
            isBridgeRunning = false

            // Unregister status receiver
            try {
                context.unregisterReceiver(statusReceiver)
            } catch (e: Exception) {
            }

        } catch (e: Exception) {
            AppLogger.functionError(TAG, "stopBridgeService", e)
        }
    }

    /**
     * Sends a broadcast through the bridge service.
     * This ensures communication persists even if the main app process is killed.
     */
    fun sendBridgedBroadcast(context: Context, intent: Intent) {
        try {
            // Ensure bridge is running
            ensureBridgeRunning(context)

            // Send the broadcast - it will be picked up by the bridge service
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "sendBridgedBroadcast", e)
        }
    }

    /**
     * Checks if the bridge service is currently running
     */
    fun isBridgeServiceRunning(): Boolean {
        return isBridgeRunning && System.currentTimeMillis() - lastStatusCheck < 60000 // 1 minute
    }

    /**
     * Cleans up resources when the app is destroyed
     */
    fun cleanup(context: Context) {
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}