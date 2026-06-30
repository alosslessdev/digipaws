package neth.iecal.curbox.services

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.anti_stimulants.AutoDnd
import neth.iecal.curbox.anti_stimulants.GrayScaleFilter
import neth.iecal.curbox.blockers.AntiUninstallBlocker
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.utils.CurboxProtectionStore

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
    private val antiUninstallBlocker = AntiUninstallBlocker()

    private var grayScaleFilter = GrayScaleFilter()

    private var lastBlockTimestampSeen: Long
        get() = CurboxProtectionStore.getLastBlockTimestampSeen(this)
        set(value) = CurboxProtectionStore.setLastBlockTimestampSeen(this, value)

    private var curboxProtectionDeadline: Long
        get() = CurboxProtectionStore.getDeadline(this)
        set(value) = CurboxProtectionStore.setDeadline(this, value)

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
        crashLogger = CrashLogger(this)
        try {
            rikka.shizuku.ShizukuProvider.requestBinderForNonProviderProcess(this)
        } catch (e: Exception) {
            Log.e("Shizuku", "Failed to bind Shizuku in non-provider process", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        Log.d(TAG, "onAccessibilityEvent: ${event.packageName} ${AccessibilityEvent.eventTypeToString(event.eventType)}")
        super.onAccessibilityEvent(event)

        val packageName = event.packageName?.toString()
        if (packageName == "neth.iecal.curbox") {
            handleCurboxProtection(event.className?.toString())
        }

        try {
            antiUninstallBlocker.doAntiUninstallCheck(event)
            appBlocker.doAppBlockerCheck(event)
            grayScaleFilter.doGrayscaleCheck(event)
            focusModeBlocker.doFocusModeCheck(event)
        } catch (t: Throwable) {
            AppLogger.functionError(TAG, "onAccessibilityEvent", t)
            crashLogger.logNonFatalError(Exception(t))
        }

        val eventCopy = AccessibilityEvent.obtain(event)
        val result = eventChannel.trySend(eventCopy)

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    private var lastWarningShowTime = 0L

    private fun handleCurboxProtection(className: String?) {
        if (className == "neth.iecal.curbox.ui.activity.WarningActivity" || 
            className == "neth.iecal.curbox.ui.activity.PortraitCaptureActivity") {
            return
        }

        val now = System.currentTimeMillis()
        val currentBlockTimestamp = lastBackPressTimeStamp

        // 1. New Block Detection
        if (currentBlockTimestamp != lastBlockTimestampSeen) {
            val sinceBlock = now - currentBlockTimestamp
            if (currentBlockTimestamp > 0 && sinceBlock in 0..<15000) {
                curboxProtectionDeadline = currentBlockTimestamp + 15000
            } else {
                curboxProtectionDeadline = 0L
            }
            lastBlockTimestampSeen = currentBlockTimestamp
        }

        // 2. Deadline Enforcement
        val deadline = curboxProtectionDeadline
        if (deadline > now) {
            if (now - lastWarningShowTime > 1500) {
                lastWarningShowTime = now
                
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(this, WarningActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                        putExtra("result_id", "neth.iecal.curbox")
                        putExtra("warning_config", Gson().toJson(AppBlockerWarningScreenConfig(
                            message = "Wait a moment before opening Curbox right after a block.",
                            proceedDelayInSecs = 5
                        )))
                    }
                    startActivity(intent)
                }, 10)
            }
        }
    }

    private val curboxProtectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == KeywordBlocker.INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN) {
                if (intent.getStringExtra("result_id") == "neth.iecal.curbox") {
                    curboxProtectionDeadline = 0L
                }
            }
        }
    }

    override fun onInterrupt() {
    }

    private fun startBackgroundWorker() {
        serviceScope.launch {
            for (event in eventChannel) {
                try {
                    reelBlocker.doViewBlockerCheck(event)
                    keywordBlocker.checkIfUserGettingFreaky(event)
                    uiHider.doUiHiderCheck(event)
                } catch (t: Throwable) {
                    // Don't log normal coroutine cancellations as crashes
                    if (t is CancellationException) throw t

                    crashLogger.logNonFatalError(Exception(t))
                    Log.e("Blocker", "Background worker error", t)
                } finally {
                    event.recycle()
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        serviceInfo = serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }

        appBlocker.setupAppBlocker(this)
        focusModeBlocker.setupFocusMode(this)
        autoDnd.setup(this)
        reelBlocker.setupBlocker(this)
        keywordBlocker.setupBlocker(this)
        uiHider.setupBlocker(this)
        nodePicker.setupBlocker(this)
        grayScaleFilter.setup(this)
        antiUninstallBlocker.setupBlocker(this)

        focusModeBlocker.setupReceivers()
        appBlocker.setupReceivers()
        reelBlocker.setupReceivers()
        keywordBlocker.setupReceivers()
        grayScaleFilter.setupReceivers()
        uiHider.setupReceivers()
        nodePicker.setupReceivers()

        val filter = IntentFilter(KeywordBlocker.INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(curboxProtectionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(curboxProtectionReceiver, filter)
        }

        startBackgroundWorker()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(curboxProtectionReceiver)
            focusModeBlocker.removeReceivers()
            autoDnd.stop()
            reelBlocker.removeReceivers()
            appBlocker.onDestroy()
            keywordBlocker.removeReceivers()
            grayScaleFilter.unregisterReceivers()
            uiHider.removeReceivers()
            nodePicker.removeReceivers()
            antiUninstallBlocker.onDestroy()

            eventChannel.close()
            serviceScope.cancel()
        }catch (_: Exception){}
    }
}
