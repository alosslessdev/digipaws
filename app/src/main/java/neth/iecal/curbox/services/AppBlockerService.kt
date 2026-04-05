package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.viewblocker.ElementPickerNotification
import neth.iecal.curbox.blockers.viewblocker.ViewBlocker
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker.ViewBlockerFragment
import neth.iecal.curbox.utils.AppLogger

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val TAG = "AppBlockerService"
    private val appBlocker: AppBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val reelBlocker = ReelBlocker()
    private var keywordBlocker = KeywordBlocker()
    private val viewBlocker = ViewBlocker()
    private var pickerNotification: ElementPickerNotification? = null

    private val pickerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            AppLogger.logDebug(TAG, "pickerReceiver.onReceive() - action=${intent?.action}")
            try {
                when (intent?.action) {
                    ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION -> {
                        AppLogger.logBlockerAction(TAG, "Picker", "Show notification")
                        pickerNotification?.showNotification()
                    }
                    ElementPickerNotification.ACTION_START_PICKER -> {
                        AppLogger.logBlockerAction(TAG, "Picker", "Start picking")
                        val picker = viewBlocker.elementPicker
                        if (picker != null && !picker.isActive) {
                            picker.show()
                            pickerNotification?.showPickerActiveNotification()
                            AppLogger.logBlockerAction(TAG, "Picker", "Picker shown", "SUCCESS")
                        }
                    }
                    ElementPickerNotification.ACTION_STOP_PICKER -> {
                        AppLogger.logBlockerAction(TAG, "Picker", "Stop picking")
                        viewBlocker.elementPicker?.hide()
                        pickerNotification?.cancelNotification()
                        AppLogger.logBlockerAction(TAG, "Picker", "Picker hidden", "SUCCESS")
                    }
                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "pickerReceiver.onReceive", e)
            }
        }
    }


    private val heartbeatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == CommunicationBridgeService.ACTION_PING) {
                val pongIntent = Intent(CommunicationBridgeService.ACTION_PONG).apply {
                    setPackage(packageName)
                }
                sendBroadcast(pongIntent)
            }
        }
    }

    private var grayScaleFilter = GrayScaleFilter()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        AppLogger.logDebug(TAG, "Event channel dropping event: ${droppedEvent.eventType}")
        droppedEvent.recycle()
    }

    private lateinit var crashLogger: CrashLogger

    override fun onCreate() {
        AppLogger.logServiceLifecycle(TAG, "onCreate()", "AppBlockerService being created")
        super.onCreate()
        try {
            crashLogger = CrashLogger(this)
            AppLogger.logDebug(TAG, "CrashLogger initialized")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onCreate", e)
        }
        
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
            AppLogger.logDebug(TAG, "Shizuku binder requested")
        } catch (e: Exception) {
            AppLogger.logWarn(TAG, "Failed to bind Shizuku in non-provider process: ${e.message}")
        }

        // Initialize AppSuspendHelper with service scope
        neth.iecal.curbox.utils.AppSuspendHelper.init(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
        android.util.Log.e("AppBlockerService", "onInterrupt() called - service interrupted")
        AppLogger.logServiceLifecycle(TAG, "onInterrupt()", "Accessibility service interrupted - system is disabling this service")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.e("AppBlockerService", "onUnbind() called - service unbinding")
        AppLogger.logServiceLifecycle(TAG, "onUnbind()", "Accessibility service unbound - potentially marked as malfunctioning or disabled")
        
        // Ensure cleanup happens even if onDestroy isn't reached immediately
        try {
            cleanup()
        } catch (e: Exception) {
            android.util.Log.e("AppBlockerService", "Error during cleanup in onUnbind", e)
        }
        
        return super.onUnbind(intent)
    }

    private fun cleanup() {
        AppLogger.logDebug(TAG, "Executing shared cleanup...")
        // Move common cleanup logic here if needed
    }

    private fun startBackgroundWorker() {
        AppLogger.logServiceLifecycle(TAG, "startBackgroundWorker()", "Background event processing started")
        serviceScope.launch {
            // Heartbeat to confirm background thread is alive
            launch {
                while (isActive) {
                    android.util.Log.d("AppBlockerService", "Heartbeat - Background thread is running...")
                    delay(30000) // Every 30 seconds
                }
            }

            try {
                AppLogger.logDebug(TAG, "Background worker coroutine started")
                for (event in eventChannel) {
                    var eventHandled = false
                    try {
                        // Process blockers in background
                        try {
                            appBlocker.doAppBlockerCheck(event)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Error in appBlocker.doAppBlockerCheck", t)
                        }

                        try {
                            grayScaleFilter.doGrayscaleCheck(event)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Error in grayScaleFilter.doGrayscaleCheck", t)
                        }

                        try {
                            focusModeBlocker.doFocusModeCheck(event)
                        } catch (t: Throwable) {
                            android.util.Log.e(TAG, "Error in focusModeBlocker.doFocusModeCheck", t)
                        }
                        
                        reelBlocker.doViewBlockerCheck(event)
                        keywordBlocker.checkIfUserGettingFreaky(event)
                        viewBlocker.doViewBlockerCheck(event)
                        
                        eventHandled = true
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        android.util.Log.e(TAG, "Critical error in background worker loop", t)
                    } finally {
                        try {
                            event.recycle()
                        } catch (e: Exception) {
                            // Already recycled or invalid
                        }
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    android.util.Log.e(TAG, "Background worker terminated unexpectedly", t)
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        AppLogger.logServiceLifecycle(TAG, "onServiceConnected()", "Service is now properly connected to accessibility framework")
        try {
            super.onServiceConnected()
            
            AppLogger.logDebug(TAG, "Setting up all blockers...")
            
            AppLogger.functionEntry(TAG, "appBlocker.setupAppBlocker")
            appBlocker.setupAppBlocker(this)
            AppLogger.functionExit(TAG, "appBlocker.setupAppBlocker")
            
            AppLogger.functionEntry(TAG, "focusModeBlocker.setupFocusMode")
            focusModeBlocker.setupFocusMode(this)
            AppLogger.functionExit(TAG, "focusModeBlocker.setupFocusMode")
            
            AppLogger.functionEntry(TAG, "reelBlocker.setupBlocker")
            reelBlocker.setupBlocker(this)
            AppLogger.functionExit(TAG, "reelBlocker.setupBlocker")
            
            AppLogger.functionEntry(TAG, "keywordBlocker.setupBlocker")
            keywordBlocker.setupBlocker(this)
            AppLogger.functionExit(TAG, "keywordBlocker.setupBlocker")
            
            AppLogger.functionEntry(TAG, "viewBlocker.setupBlocker")
            viewBlocker.setupBlocker(this)
            AppLogger.functionExit(TAG, "viewBlocker.setupBlocker")
            
            AppLogger.functionEntry(TAG, "viewBlocker.setupElementPicker")
            viewBlocker.setupElementPicker()
            AppLogger.functionExit(TAG, "viewBlocker.setupElementPicker")
            
            AppLogger.functionEntry(TAG, "ElementPickerNotification constructor")
            pickerNotification = ElementPickerNotification(this)
            AppLogger.functionExit(TAG, "ElementPickerNotification constructor")
            
            AppLogger.functionEntry(TAG, "grayScaleFilter.setup")
            grayScaleFilter.setup(this)
            AppLogger.functionExit(TAG, "grayScaleFilter.setup")

            AppLogger.logDebug(TAG, "Setting up receivers...")
            
            AppLogger.functionEntry(TAG, "focusModeBlocker.setupReceivers")
            focusModeBlocker.setupReceivers()
            AppLogger.functionExit(TAG, "focusModeBlocker.setupReceivers")
            
            AppLogger.functionEntry(TAG, "appBlocker.setupReceivers")
            appBlocker.setupReceivers()
            AppLogger.functionExit(TAG, "appBlocker.setupReceivers")
            
            AppLogger.functionEntry(TAG, "reelBlocker.setupReceivers")
            reelBlocker.setupReceivers()
            AppLogger.functionExit(TAG, "reelBlocker.setupReceivers")
            
            AppLogger.functionEntry(TAG, "keywordBlocker.setupReceivers")
            keywordBlocker.setupReceivers()
            AppLogger.functionExit(TAG, "keywordBlocker.setupReceivers")
            
            AppLogger.functionEntry(TAG, "grayScaleFilter.setupReceivers")
            grayScaleFilter.setupReceivers()
            AppLogger.functionExit(TAG, "grayScaleFilter.setupReceivers")
            
            AppLogger.functionEntry(TAG, "viewBlocker.setupReceivers")
            viewBlocker.setupReceivers()
            AppLogger.functionExit(TAG, "viewBlocker.setupReceivers")

            val pickerFilter = IntentFilter().apply {
                addAction(ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION)
                addAction(ElementPickerNotification.ACTION_START_PICKER)
                addAction(ElementPickerNotification.ACTION_STOP_PICKER)
            }
            
            AppLogger.logDebug(TAG, "Registering picker receiver")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pickerReceiver, pickerFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pickerReceiver, pickerFilter)
            }
            AppLogger.logDebug(TAG, "Picker receiver registered")

            val heartbeatFilter = IntentFilter(CommunicationBridgeService.ACTION_PING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(heartbeatReceiver, heartbeatFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(heartbeatReceiver, heartbeatFilter)
            }

            AppLogger.logDebug(TAG, "Starting background worker")
            startBackgroundWorker()
            
            AppLogger.logServiceLifecycle(TAG, "onServiceConnected COMPLETE", "All blockers and receivers setup successfully")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onServiceConnected", e)
            crashLogger.logNonFatalError(Exception(e))
        }
    }

    override fun onDestroy() {
        AppLogger.logServiceLifecycle(TAG, "onDestroy()", "AppBlockerService is being destroyed")
        try {
            super.onDestroy()

            AppLogger.logDebug(TAG, "Removing all receivers and shutting down blockers...")
            
            try {
                AppLogger.functionEntry(TAG, "focusModeBlocker.removeReceivers")
                focusModeBlocker.removeReceivers()
                AppLogger.functionExit(TAG, "focusModeBlocker.removeReceivers")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "focusModeBlocker.removeReceivers", e)
            }
            
            try {
                AppLogger.functionEntry(TAG, "reelBlocker.removeReceivers")
                reelBlocker.removeReceivers()
                AppLogger.functionExit(TAG, "reelBlocker.removeReceivers")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "reelBlocker.removeReceivers", e)
            }
            
            try {
                AppLogger.functionEntry(TAG, "appBlocker.onDestroy")
                appBlocker.onDestroy()
                AppLogger.functionExit(TAG, "appBlocker.onDestroy")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "appBlocker.onDestroy", e)
            }
            
            try {
                AppLogger.functionEntry(TAG, "keywordBlocker.removeReceivers")
                keywordBlocker.removeReceivers()
                AppLogger.functionExit(TAG, "keywordBlocker.removeReceivers")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "keywordBlocker.removeReceivers", e)
            }
            
            try {
                AppLogger.functionEntry(TAG, "grayScaleFilter.unregisterReceivers")
                grayScaleFilter.unregisterReceivers()
                AppLogger.functionExit(TAG, "grayScaleFilter.unregisterReceivers")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "grayScaleFilter.unregisterReceivers", e)
            }
            
            try {
                AppLogger.functionEntry(TAG, "viewBlocker.removeReceivers")
                viewBlocker.removeReceivers()
                AppLogger.functionExit(TAG, "viewBlocker.removeReceivers")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "viewBlocker.removeReceivers", e)
            }
            
            try {
                AppLogger.logDebug(TAG, "Unregistering picker receiver")
                unregisterReceiver(pickerReceiver)
                AppLogger.logDebug(TAG, "Picker receiver unregistered")
            } catch (e: Exception) {
                AppLogger.logWarn(TAG, "Failed to unregister picker receiver: ${e.message}")
            }

            try {
                unregisterReceiver(heartbeatReceiver)
            } catch (e: Exception) {
                // ignore
            }
            
            try {
                AppLogger.functionEntry(TAG, "pickerNotification.cancelNotification")
                pickerNotification?.cancelNotification()
                AppLogger.functionExit(TAG, "pickerNotification.cancelNotification")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "pickerNotification.cancelNotification", e)
            }

            try {
                AppLogger.logDebug(TAG, "Closing event channel")
                eventChannel.close()
                AppLogger.logDebug(TAG, "Event channel closed")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "eventChannel.close", e)
            }
            
            try {
                AppLogger.logDebug(TAG, "Cancelling service scope")
                serviceScope.cancel()
                AppLogger.logDebug(TAG, "Service scope cancelled")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "serviceScope.cancel", e)
            }
            
            AppLogger.logServiceLifecycle(TAG, "onDestroy COMPLETE", "All resources cleaned up")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}