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
            try {
                when (intent?.action) {
                    ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION -> {
                        pickerNotification?.showNotification()
                    }
                    ElementPickerNotification.ACTION_START_PICKER -> {
                        val picker = viewBlocker.elementPicker
                        if (picker != null && !picker.isActive) {
                            picker.show()
                            pickerNotification?.showPickerActiveNotification()
                        }
                    }
                    ElementPickerNotification.ACTION_STOP_PICKER -> {
                        viewBlocker.elementPicker?.hide()
                        pickerNotification?.cancelNotification()
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
        droppedEvent.recycle()
    }

    private lateinit var crashLogger: CrashLogger

    override fun onCreate() {
        super.onCreate()
        try {
            crashLogger = CrashLogger(this)
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onCreate", e)
        }
        
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
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
    }

    override fun onUnbind(intent: Intent?): Boolean {
        android.util.Log.e("AppBlockerService", "onUnbind() called - service unbinding")
        
        // Ensure cleanup happens even if onDestroy isn't reached immediately
        try {
            cleanup()
        } catch (e: Exception) {
            android.util.Log.e("AppBlockerService", "Error during cleanup in onUnbind", e)
        }
        
        return super.onUnbind(intent)
    }

    private fun cleanup() {
        // Move common cleanup logic here if needed
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            try {
                for (event in eventChannel) {
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
        try {
            super.onServiceConnected()
            
            appBlocker.setupAppBlocker(this)
            focusModeBlocker.setupFocusMode(this)
            reelBlocker.setupBlocker(this)
            keywordBlocker.setupBlocker(this)
            viewBlocker.setupBlocker(this)
            viewBlocker.setupElementPicker()
            pickerNotification = ElementPickerNotification(this)
            grayScaleFilter.setup(this)

            focusModeBlocker.setupReceivers()
            appBlocker.setupReceivers()
            reelBlocker.setupReceivers()
            keywordBlocker.setupReceivers()
            grayScaleFilter.setupReceivers()
            viewBlocker.setupReceivers()

            val pickerFilter = IntentFilter().apply {
                addAction(ViewBlockerFragment.INTENT_ACTION_SHOW_PICKER_NOTIFICATION)
                addAction(ElementPickerNotification.ACTION_START_PICKER)
                addAction(ElementPickerNotification.ACTION_STOP_PICKER)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(pickerReceiver, pickerFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(pickerReceiver, pickerFilter)
            }

            val heartbeatFilter = IntentFilter(CommunicationBridgeService.ACTION_PING)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(heartbeatReceiver, heartbeatFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(heartbeatReceiver, heartbeatFilter)
            }

            startBackgroundWorker()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onServiceConnected", e)
            crashLogger.logNonFatalError(Exception(e))
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()

            try {
                focusModeBlocker.removeReceivers()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "focusModeBlocker.removeReceivers", e)
            }
            
            try {
                reelBlocker.removeReceivers()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "reelBlocker.removeReceivers", e)
            }
            
            try {
                appBlocker.onDestroy()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "appBlocker.onDestroy", e)
            }
            
            try {
                keywordBlocker.removeReceivers()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "keywordBlocker.removeReceivers", e)
            }
            
            try {
                grayScaleFilter.unregisterReceivers()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "grayScaleFilter.unregisterReceivers", e)
            }
            
            try {
                viewBlocker.removeReceivers()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "viewBlocker.removeReceivers", e)
            }
            
            try {
                unregisterReceiver(pickerReceiver)
            } catch (e: Exception) {
                AppLogger.logWarn(TAG, "Failed to unregister picker receiver: ${e.message}")
            }

            try {
                unregisterReceiver(heartbeatReceiver)
            } catch (e: Exception) {
                // ignore
            }
            
            try {
                pickerNotification?.cancelNotification()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "pickerNotification.cancelNotification", e)
            }

            try {
                eventChannel.close()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "eventChannel.close", e)
            }
            
            try {
                serviceScope.cancel()
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "serviceScope.cancel", e)
            }
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}