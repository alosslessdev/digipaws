package neth.iecal.curbox.utils

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

/**
 * Comprehensive logging utility for debugging app crashes and behavior.
 * Logs to both Logcat and a persistent log file.
 */
object AppLogger {
    private lateinit var logFile: File
    private const val TAG = "DigiPaws"
    private const val LOG_FILENAME = "app_debug.log"
    private val logExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    fun init(context: Context) {
        logFile = File(context.cacheDir, LOG_FILENAME)
        // Clear old logs if file is too large (> 5MB)
        if (logFile.exists() && logFile.length() > 5 * 1024 * 1024) {
            logFile.delete()
        }
    }
    
    // ENTRY/EXIT LOGGING FOR FUNCTION CALLS
    fun functionEntry(tag: String, functionName: String, args: String = "") {
        val message = ">>> ENTER: $functionName ${if (args.isNotEmpty()) "($args)" else "()"}"
        logDebug(tag, message)
    }
    
    fun functionExit(tag: String, functionName: String, returnValue: Any? = null) {
        val message = "<<< EXIT: $functionName ${if (returnValue != null) "=> $returnValue" else ""}"
        logDebug(tag, message)
    }
    
    fun functionError(tag: String, functionName: String, error: Throwable) {
        val message = "!!! ERROR in $functionName: ${error.message}"
        logError(tag, message, error)
    }
    
    // VARIABLE LOGGING
    fun logVariable(tag: String, varName: String, value: Any?) {
        val message = "VAR: $varName = $value"
        logDebug(tag, message)
    }
    
    fun logServiceLifecycle(tag: String, event: String, details: String = "") {
        val message = "[SERVICE] $event ${if (details.isNotEmpty()) "- $details" else ""}"
        logInfo(tag, message)
    }
    
    fun logAccessibilityEvent(tag: String, eventType: String, appName: String = "", details: String = "") {
        val message = "[EVENT] Type=$eventType App=$appName ${if (details.isNotEmpty()) "Details=$details" else ""}"
        logDebug(tag, message)
    }
    
    fun logBlockerAction(tag: String, blockerName: String, action: String, result: String = "") {
        val message = "[$blockerName] $action ${if (result.isNotEmpty()) "=> $result" else ""}"
        logInfo(tag, message)
    }
    
    fun logStateChange(tag: String, component: String, oldState: String, newState: String) {
        val message = "[STATE] $component: $oldState -> $newState"
        logInfo(tag, message)
    }
    
    // STANDARD LOGGING
    fun logDebug(tag: String, message: String) {
        Log.d(TAG, "[$tag] $message")
        writeToFile("D", tag, message)
    }
    
    fun logInfo(tag: String, message: String) {
        Log.i(TAG, "[$tag] $message")
        writeToFile("I", tag, message)
    }
    
    fun logWarn(tag: String, message: String) {
        Log.w(TAG, "[$tag] $message")
        writeToFile("W", tag, message)
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(TAG, "[$tag] $message", throwable)
        writeToFile("E", tag, message, throwable)
    }
    
    private fun writeToFile(level: String, tag: String, message: String, throwable: Throwable? = null) {
        logExecutor.execute {
            try {
                if (!::logFile.isInitialized) return@execute
                
                val timeStamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date())
                val logEntry = "[$timeStamp] $level/$tag: $message"
                
                FileWriter(logFile, true).use { writer ->
                    writer.append("$logEntry\n")
                    
                    if (throwable != null) {
                        val printWriter = PrintWriter(writer)
                        throwable.printStackTrace(printWriter)
                        printWriter.flush()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write to log file", e)
            }
        }
    }
    
    fun clearLogs() {
        try {
            if (::logFile.isInitialized && logFile.exists()) {
                logFile.delete()
                logInfo("AppLogger", "Log file cleared")
            }
        } catch (e: Exception) {
            logError("AppLogger", "Failed to clear log file", e)
        }
    }
}
