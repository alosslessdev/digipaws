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
import androidx.core.content.edit
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
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.uihider.NodePicker
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.ui.activity.WarningActivity
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

    private var lastBlockTimestampSeen: Long
        get() = getSharedPreferences("AppPreferences", MODE_PRIVATE).getLong("lastBlockTimestampSeen", 0L)
        set(value) = getSharedPreferences("AppPreferences", MODE_PRIVATE).edit(commit = true) { putLong("lastBlockTimestampSeen", value) }

    private var curboxProtectionDeadline: Long
        get() = getSharedPreferences("AppPreferences", MODE_PRIVATE).getLong("curboxProtectionDeadline", 0L)
        set(value) = getSharedPreferences("AppPreferences", MODE_PRIVATE).edit(commit = true) { putLong("curboxProtectionDeadline", value) }

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
        super.onAccessibilityEvent(event)

        val packageName = event.packageName?.toString()
        if (packageName == "neth.iecal.curbox") {
            val className = event.className?.toString()
            // Always check for protection if we are in Curbox, but avoid looping on WarningActivity
            if (className != "neth.iecal.curbox.ui.activity.WarningActivity" && 
                className != "neth.iecal.curbox.ui.activity.PortraitCaptureActivity") {
                handleCurboxProtection()
            }
        }

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

        // If the channel is closed or rejected it, recycle immediately
        if (result.isFailure) {
            eventCopy.recycle()
        }
    }

    private var lastWarningShowTime = 0L

    private fun handleCurboxProtection() {
        val currentBlockTimestamp = lastBackPressTimeStamp
        val now = System.currentTimeMillis()

        if (currentBlockTimestamp != lastBlockTimestampSeen) {
            if (now - currentBlockTimestamp < 15000) {
                curboxProtectionDeadline = now + 15000
                lastBlockTimestampSeen = currentBlockTimestamp
            } else {
                curboxProtectionDeadline = 0L
                lastBlockTimestampSeen = currentBlockTimestamp
            }
        }

        val deadline = curboxProtectionDeadline
        if (deadline > now && now - lastWarningShowTime > 1000) {
            lastWarningShowTime = now
            val remainingSeconds = ((deadline - now) / 1000).toInt().coerceAtLeast(1)
            Handler(Looper.getMainLooper()).postDelayed({
                val intent = Intent(this, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                    putExtra("result_id", "neth.iecal.curbox")
                    putExtra("warning_config", Gson().toJson(AppBlockerWarningScreenConfig(
                        message = "Wait a moment before opening Curbox right after a block.",
                        proceedDelayInSecs = remainingSeconds
                    )))
                }
                startActivity(intent)
            }, 10)
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

        val filter = IntentFilter(KeywordBlocker.INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(curboxProtectionReceiver, filter, Context.RECEIVER_EXPORTED)
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

            eventChannel.close()
            serviceScope.cancel()
        }catch (_: Exception){}
    }
}