package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.AppLogger
import java.util.Calendar

class ReelBlocker : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER = "neth.iecal.curbox.refresh.reelblocker"
        const val INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN =
            "neth.iecal.curbox.refresh.reelblocker.cooldown"

        fun findElementById(node: AccessibilityNodeInfo?, id: String?): AccessibilityNodeInfo? {
            if (node == null || id == null) return null
            try {
                val nodes = node.findAccessibilityNodeInfosByViewId(id)
                if (nodes.isNotEmpty()) {
                    val result = nodes[0]
                    for (i in 1 until nodes.size) {
                        try { nodes[i].recycle() } catch (_: Exception) {}
                    }
                    return result
                }
            } catch (_: Exception) {}
            return null
        }

        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED
    }
    
    private val TAG = "ReelBlocker"
    private lateinit var service : BaseBlockingService

    private var reelBlockerConfig: ReelBlocker = ReelBlocker(isActive = false)
    private var timeBAsedConfig : ReelTimeConfig? = null
    private var countBasedConfig : ReelCountConfig? = null
    private var currentDailyCount: Int = 0
    private var currentCountDate: String? = null
    private var settingsJob: Job? = null
    private var countJob: Job? = null
    
    private val cooldownViewIdsList = mutableMapOf<String, Long>()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var lastEventTimeStamp = 0L

    private lateinit var notificationManager: TimerNotification

    fun doViewBlockerCheck(event: AccessibilityEvent?) {
        fun showWarningScreen(viewId: String) {
            try {
                if (service.isDelayOver(1000)) {
                    service.pressBack()
                    if (reelBlockerConfig.warningScreenConfig.isWarningDialogHidden) {
                        return
                    }
                    val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("mode", Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER)
                        putExtra("result_id", viewId)
                        putExtra("warning_config", Gson().toJson(reelBlockerConfig.warningScreenConfig))
                    }
                    service.startActivity(dialogIntent)
                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "showWarningScreen", e)
            }
        }
        
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return
        if (!reelBlockerConfig.isActive) return

        val node = service.rootInActiveWindow ?: return
        val pkg = event.packageName?.toString() ?: return
        val data = reelData[pkg] ?: return
        val viewId = data.viewId

        if (isViewOpened(node, viewId)) {
            Log.d(TAG, "view found: $viewId")
            for (req in data.requiresPresent) {
                if (!NodeFinder.exists(node, req)) return
            }
            for (req in data.requiresAbsent) {
                if (NodeFinder.exists(node, req)) return
            }

            if (isCooldownActive(viewId)) return

            when (reelBlockerConfig.blockingType) {
                ReelBlockingType.TIMED -> {
                    if (isTimedBlockActive()) {
                        showWarningScreen(viewId)
                    }
                }
                ReelBlockingType.USAGE -> { /* TODO */ }
                ReelBlockingType.REEL_COUNT -> {
                    ensureCountFlowForToday()
                    val limit = getDailyReelCountLimit()
                    if (limit != null && limit > 0 && currentDailyCount >= limit) {
                        showWarningScreen(viewId)
                    }
                }
            }
        }
        lastEventTimeStamp = SystemClock.uptimeMillis()
    }

    fun applyCooldown(viewId: String, endTime: Long) {
        try {
            val remainingTime = endTime - SystemClock.uptimeMillis()
            notificationManager.startTimer(totalMillis = remainingTime, timerId = viewId, title = "Remaining usage before reels lockdown")
            cooldownViewIdsList[viewId] = endTime
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "applyCooldown", e)
        }
    }

    fun setupBlocker(service: BaseBlockingService) {
        try {
            this.service = service
            notificationManager = TimerNotification(service)
            
            val displayMetrics: DisplayMetrics = service.resources.displayMetrics
            screenHeight = displayMetrics.heightPixels
            screenWidth = displayMetrics.widthPixels

            settingsJob?.cancel()
            settingsJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    service.dataStoreManager.settings.collectLatest { settings ->
                        reelBlockerConfig = settings.reelBlockerConfig
                        when (reelBlockerConfig.blockingType) {
                            ReelBlockingType.TIMED -> {
                                timeBAsedConfig = Gson().fromJson(settings.reelBlockerConfig.settings, ReelTimeConfig::class.java)
                            }
                            ReelBlockingType.USAGE -> {}
                            ReelBlockingType.REEL_COUNT -> {
                                countBasedConfig = Gson().fromJson(settings.reelBlockerConfig.settings, ReelCountConfig::class.java)
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.functionError(TAG, "setupBlocker.settingsJob", e)
                }
            }
            ensureCountFlowForToday()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupBlocker", e)
        }
    }

    private fun ensureCountFlowForToday() {
        val today = TimeTools.getCurrentDate()
        if (today != currentCountDate) {
            launchCountFlow(today)
        }
    }

    private fun launchCountFlow(date: String) {
        countJob?.cancel()
        currentCountDate = date
        val db = AppDatabase.getInstance(service)
        countJob = CoroutineScope(Dispatchers.IO).launch {
            db.reelStatsDao().getCountFlow(date).collectLatest { count ->
                currentDailyCount = count ?: 0
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers(){
        try {
            val filter = IntentFilter().apply {
                addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER)
                addAction(INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(refreshReceiver, filter)
            }
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupReceivers", e)
        }
    }

    fun removeReceivers(){
        try {
            service.unregisterReceiver(refreshReceiver)
            notificationManager.release()
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "removeReceivers", e)
        }
        settingsJob?.cancel()
        countJob?.cancel()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_REEL_BLOCKER -> setupBlocker(service)
                INTENT_ACTION_REFRESH_REEL_BLOCKER_COOLDOWN -> {
                    val interval = intent.getIntExtra("selected_time", reelBlockerConfig.warningScreenConfig.timeInterval)
                    applyCooldown(
                        intent.getStringExtra("result_id") ?: "xxxxxxxxxxxxxx",
                        SystemClock.uptimeMillis() + interval
                    )
                }
            }
        }
    }

    private fun isCooldownActive(viewId: String): Boolean {
        val cooldownEnd = cooldownViewIdsList[viewId] ?: return false
        if (SystemClock.uptimeMillis() > cooldownEnd) {
            cooldownViewIdsList.remove(viewId)
            return false
        }
        return true
    }

    private fun isViewOpened(rootNode: AccessibilityNodeInfo, viewId: String): Boolean {
        val viewNode = NodeFinder.findFirst(rootNode, viewId) ?: return false
        val nodeRect = Rect()
        viewNode.getBoundsInScreen(nodeRect)
        NodeFinder.recycle(viewNode)
        val isOffScreenLeft = nodeRect.right <= 0
        val isOffScreenRight = nodeRect.left >= screenWidth
        return !isOffScreenLeft && !isOffScreenRight
    }

    private fun getDailyReelCountLimit(): Int? {
        val config = countBasedConfig ?: return null
        return if (config.isDailyUniform) config.uniformLimit
        else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun isTimedBlockActive(): Boolean {
        if (timeBAsedConfig == null) return false
        val config = timeBAsedConfig!!
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE))
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals
                        else config.dailyIntervals[dayOfWeek] ?: emptyList()

        for (interval in intervals) {
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) return true
            } else {
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) return true
            }
        }
        return false
    }
}
