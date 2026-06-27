package neth.iecal.curbox.services

import android.annotation.SuppressLint
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
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.anti_stimulants.AutoDnd
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.utils.AppLogger

@Suppress("DEPRECATION")
class AppBlockerService : BaseBlockingService() {

    private val TAG = "AppBlockerService"
    private val appBlocker: AppBlocker = AppBlocker()
    private val focusModeBlocker = FocusModeBlocker()
    private val autoDnd = AutoDnd()
    private val reelBlocker = ReelBlocker()
    private var keywordBlocker = KeywordBlocker()
    private val uiHider = UiHider()
    private val nodePicker = NodePicker()

    private var grayScaleFilter = GrayScaleFilter()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val eventChannel = Channel<AccessibilityEvent>(Channel.CONFLATED) { droppedEvent ->
        droppedEvent.recycle()
    }

    private lateinit var crashLogger: CrashLogger

    fun syncDndState() {
        val autoDndActive = autoDnd.isDndRequested()
        val manualFocusDndActive = focusModeBlocker.isDndRequested()
        neth.iecal.curbox.utils.DndHelper.applyDndState(this, autoDndActive || manualFocusDndActive)
    }

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

        neth.iecal.curbox.utils.AppSuspendHelper.init(serviceScope)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        super.onAccessibilityEvent(event)

        try {
            appBlocker.doAppBlockerCheck(event)
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
        } catch (t: Throwable) {
            AppLogger.functionError(TAG, "onAccessibilityEvent", t)
            crashLogger.logNonFatalError(Exception(t))
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    override fun onInterrupt() {
        Log.e(TAG, "onInterrupt() called - service interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.e(TAG, "onUnbind() called - service unbinding")
        cleanup()
        return super.onUnbind(intent)
    }

    private fun cleanup() {}

    private fun startBackgroundWorker() {
        serviceScope.launch {
            try {
                for (event in eventChannel) {
                    try {
                        reelBlocker.doViewBlockerCheck(event)
                        keywordBlocker.checkIfUserGettingFreaky(event)
                        uiHider.doUiHiderCheck(event)
                        
                    } catch (t: Throwable) {
                        if (t is CancellationException) throw t
                        Log.e(TAG, "Critical error in background worker loop", t)
                    } finally {
                        try {
                            event.recycle()
                        } catch (e: Exception) {}
                    }
                }
            } catch (t: Throwable) {
                if (t !is CancellationException) {
                    Log.e(TAG, "Background worker terminated unexpectedly", t)
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
            autoDnd.setup(this)
            reelBlocker.setupBlocker(this)
            keywordBlocker.setupBlocker(this)
            uiHider.setupBlocker(this)
            nodePicker.setupBlocker(this)
            grayScaleFilter.setup(this)

            focusModeBlocker.setupReceivers()
            appBlocker.setupReceivers()
            reelBlocker.setupReceivers()
            keywordBlocker.setupReceivers()
            grayScaleFilter.setupReceivers()
            uiHider.setupReceivers()
            nodePicker.setupReceivers()

            startBackgroundWorker()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onServiceConnected", e)
            crashLogger.logNonFatalError(Exception(e))
        }
    }

    override fun onDestroy() {
        try {
            super.onDestroy()

            try { focusModeBlocker.removeReceivers() } catch (e: Exception) {}
            try { autoDnd.stop() } catch (e: Exception) {}
            try { reelBlocker.removeReceivers() } catch (e: Exception) {}
            try { appBlocker.onDestroy() } catch (e: Exception) {}
            try { keywordBlocker.removeReceivers() } catch (e: Exception) {}
            try { grayScaleFilter.unregisterReceivers() } catch (e: Exception) {}
            try { uiHider.removeReceivers() } catch (e: Exception) {}
            try { nodePicker.removeReceivers() } catch (e: Exception) {}

            try {
                eventChannel.close()
            } catch (e: Exception) {}
            
            try {
                serviceScope.cancel()
            } catch (e: Exception) {}
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }
}
