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
                val timestamp = intent.getLongExtra("timestamp", 0L)

                AppLogger.logVariable(TAG, "bridge_service_running", running)
                AppLogger.logVariable(TAG, "status_timestamp", timestamp)

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
        AppLogger.functionEntry(TAG, "ensureBridgeRunning")

        try {
            // Check if we need to start the service
            if (!isBridgeRunning || System.currentTimeMillis() - lastStatusCheck > 30000) { // 30 seconds
                AppLogger.logDebug(TAG, "Starting communication bridge service")
                CommunicationBridgeService.startService(context)
                isBridgeRunning = true
            } else {
                AppLogger.logDebug(TAG, "Bridge service already running")
            }

            // Register status receiver if not already registered
            try {
                val filter = IntentFilter(CommunicationBridgeService.ACTION_STATUS_RESPONSE)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(statusReceiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(statusReceiver, filter)
                }
                AppLogger.logDebug(TAG, "Status receiver registered")
            } catch (e: Exception) {
                AppLogger.logWarn(TAG, "Status receiver already registered: ${e.message}")
            }

            // Request status update
            CommunicationBridgeService.checkServiceStatus(context)

        } catch (e: Exception) {
            AppLogger.functionError(TAG, "ensureBridgeRunning", e)
        }

        AppLogger.functionExit(TAG, "ensureBridgeRunning")
    }

    /**
     * Stops the communication bridge service.
     * Call this when the app is being destroyed or when accessibility services are disabled.
     */
    fun stopBridgeService(context: Context) {
        AppLogger.functionEntry(TAG, "stopBridgeService")

        try {
            CommunicationBridgeService.stopService(context)
            isBridgeRunning = false
            AppLogger.logBlockerAction(TAG, "BridgeService", "Service stopped", "SUCCESS")

            // Unregister status receiver
            try {
                context.unregisterReceiver(statusReceiver)
                AppLogger.logDebug(TAG, "Status receiver unregistered")
            } catch (e: Exception) {
                AppLogger.logWarn(TAG, "Failed to unregister status receiver: ${e.message}")
            }

        } catch (e: Exception) {
            AppLogger.functionError(TAG, "stopBridgeService", e)
        }

        AppLogger.functionExit(TAG, "stopBridgeService")
    }

    /**
     * Sends a broadcast through the bridge service.
     * This ensures communication persists even if the main app process is killed.
     */
    fun sendBridgedBroadcast(context: Context, intent: Intent) {
        AppLogger.functionEntry(TAG, "sendBridgedBroadcast", "action=${intent.action}")

        try {
            // Ensure bridge is running
            ensureBridgeRunning(context)

            // Send the broadcast - it will be picked up by the bridge service
            context.sendBroadcast(intent)
            AppLogger.logBlockerAction(TAG, "Broadcast", "Sent via bridge", "action=${intent.action}")

        } catch (e: Exception) {
            AppLogger.functionError(TAG, "sendBridgedBroadcast", e)
        }

        AppLogger.functionExit(TAG, "sendBridgedBroadcast")
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
        AppLogger.logDebug(TAG, "cleanup() - Cleaning up bridge service manager")
        try {
            context.unregisterReceiver(statusReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
    }
}