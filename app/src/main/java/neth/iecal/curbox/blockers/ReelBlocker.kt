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
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.data.db.AppDatabase
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
            if (node == null) return null
            var targetNode: AccessibilityNodeInfo? = null
            try {
                targetNode = node.findAccessibilityNodeInfosByViewId(id!!)[0]
            } catch (e: Exception) {
                //e.printStackTrace();
            }
            return targetNode
        }

        val BLOCKED_VIEW_ID_LIST = mutableListOf(
            "com.instagram.android:id/root_clips_layout",
            "com.myinsta.android:id/root_clips_layout",
            "com.google.android.youtube:id/reel_recycler",
            "app.revanced.android.youtube:id/reel_recycler"
        )

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
    private var settingsJob: Job? = null
    private var countJob: Job? = null
    
    private val cooldownViewIdsList = mutableMapOf<String, Long>()
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    private var lastEventTimeStamp = 0L

    private lateinit var notificationManager: TimerNotification

    fun doViewBlockerCheck(
        event: AccessibilityEvent?
    ){
        AppLogger.functionEntry(TAG, "doViewBlockerCheck", "eventType=${event?.eventType}")
        
        fun showWarningScreen(viewId: String){
            AppLogger.logDebug(TAG, "showWarningScreen() called for viewId=$viewId")
            try {
                if(service.isDelayOver(service.lastBackPressTimeStamp,1000)) {
                    AppLogger.logBlockerAction(TAG, "ReelBlocker", "Pressing back before showing warning")
                    service.pressBack()

                    if (reelBlockerConfig.warningScreenConfig.isWarningDialogHidden) {
                        AppLogger.logDebug(TAG, "showWarningScreen: Dialog hidden by config")
                        return
                    }
                    val dialogIntent = Intent(service, WarningActivity::class.java)
                    dialogIntent.flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    dialogIntent.putExtra("mode", Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER)
                    dialogIntent.putExtra("result_id", viewId)
                    dialogIntent.putExtra(
                        "warning_config",
                        Gson().toJson(reelBlockerConfig.warningScreenConfig)
                    )
                    AppLogger.logBlockerAction(TAG, "ReelBlocker", "Starting warning activity", "viewId=$viewId")
                    service.startActivity(dialogIntent)
                } else {
                    AppLogger.logDebug(TAG, "showWarningScreen: Delay not over yet")
                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "showWarningScreen", e)
            }
        }
        
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) {
            AppLogger.logDebug(TAG, "doViewBlockerCheck: Event is null or wrong type")
            return
        }

        if (!reelBlockerConfig.isActive) {
            AppLogger.logDebug(TAG, "doViewBlockerCheck: ReelBlocker not active")
            return
        }

        val node = service.rootInActiveWindow
        if(node==null) {
            AppLogger.logDebug(TAG, "doViewBlockerCheck: rootInActiveWindow is null")
            return
        }
        
        AppLogger.logVariable(TAG, "blocked_view_ids_to_check", BLOCKED_VIEW_ID_LIST.size)

        BLOCKED_VIEW_ID_LIST.forEach { viewId ->
            try {
                if(isViewOpened(node,viewId)){
                    AppLogger.logBlockerAction(TAG, "ReelBlocker", "Blocked view detected", "viewId=$viewId")
                    
                    // ignore if view-id under cooldown
                    if (isCooldownActive(viewId)) {
                        AppLogger.logDebug(TAG, "doViewBlockerCheck: ViewId $viewId is under cooldown")
                        return@forEach
                    }

                    // check if currently under allowed hours
                    when(reelBlockerConfig.blockingType) {
                        ReelBlockingType.TIMED -> {
                            AppLogger.logDebug(TAG, "doViewBlockerCheck: Checking time-based blocking for $viewId")
                            val endAllowedMillis = getEndTimeInMillis()
                            AppLogger.logVariable(TAG, "end_allowed_millis", endAllowedMillis)
                            if(endAllowedMillis==null) {
                                AppLogger.logBlockerAction(TAG, "ReelBlocker", "Outside allowed time window", "viewId=$viewId")
                                showWarningScreen(viewId)
                            }
                        }
                        ReelBlockingType.USAGE -> {
                            AppLogger.logDebug(TAG, "doViewBlockerCheck: USAGE blocking not implemented yet")
                        }
                        ReelBlockingType.REEL_COUNT -> {
                            AppLogger.logDebug(TAG, "doViewBlockerCheck: Checking count-based blocking for $viewId")
                            val limit = getDailyReelCountLimit()
                            AppLogger.logVariable(TAG, "daily_reel_limit", limit)
                            AppLogger.logVariable(TAG, "current_daily_count", currentDailyCount)
                            if (limit != null && limit > 0 && currentDailyCount >= limit) {
                                AppLogger.logBlockerAction(TAG, "ReelBlocker", "Daily reel count exceeded", "current=$currentDailyCount, limit=$limit")
                                showWarningScreen(viewId)
                            }
                        }
                    }

                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "doViewBlockerCheck.forEach", e)
            }
        }
        lastEventTimeStamp = SystemClock.uptimeMillis()
        AppLogger.functionExit(TAG, "doViewBlockerCheck")
    }


    fun applyCooldown(viewId: String, endTime: Long) {
        AppLogger.logDebug(TAG, "applyCooldown() called for viewId=$viewId, endTime=$endTime")
        try {
            val remainingTime = endTime - SystemClock.uptimeMillis()
            AppLogger.logVariable(TAG, "cooldown_remaining_ms", remainingTime)
            notificationManager.startTimer(totalMillis = remainingTime, timerId = viewId, title = "Remaining usage before reels lockdown")
            cooldownViewIdsList[viewId] = endTime
            AppLogger.logBlockerAction(TAG, "ReelBlocker", "Cooldown applied", "viewId=$viewId, duration=${remainingTime}ms")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "applyCooldown", e)
        }
    }


    fun setupBlocker(service: BaseBlockingService) {
        AppLogger.logBlockerAction(TAG, "ReelBlocker", "Setup started")
        try {
            this.service = service
            AppLogger.logDebug(TAG, "setupBlocker: Service assigned")

            notificationManager = TimerNotification(service)
            AppLogger.logDebug(TAG, "setupBlocker: NotificationManager created")
            
            var displayMetrics: DisplayMetrics = service.resources.displayMetrics
            screenHeight = displayMetrics.heightPixels
            screenWidth = displayMetrics.widthPixels
            AppLogger.logVariable(TAG, "screen_width", screenWidth)
            AppLogger.logVariable(TAG, "screen_height", screenHeight)

            settingsJob?.cancel()
            countJob?.cancel()
            AppLogger.logDebug(TAG, "setupBlocker: Previous jobs cancelled")

            settingsJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    service.dataStoreManager.settings.collectLatest { settings ->
                        AppLogger.logDebug(TAG, "setupBlocker: Settings updated from DataStore")
                        reelBlockerConfig = settings.reelBlockerConfig
                        AppLogger.logVariable(TAG, "reel_blocker_active", reelBlockerConfig.isActive)
                        AppLogger.logVariable(TAG, "reel_blocking_type", reelBlockerConfig.blockingType)
                        
                        when(reelBlockerConfig.blockingType) {
                            ReelBlockingType.TIMED -> {
                                AppLogger.logDebug(TAG, "setupBlocker: Loading time-based config")
                                timeBAsedConfig = Gson().fromJson<ReelTimeConfig>(settings.reelBlockerConfig.settings,
                                    ReelTimeConfig::class.java)
                                AppLogger.logDebug(TAG, "setupBlocker: Time-based config loaded")
                            }
                            ReelBlockingType.USAGE -> {
                                AppLogger.logDebug(TAG, "setupBlocker: USAGE blocking not implemented yet")
                            }
                            ReelBlockingType.REEL_COUNT -> {
                                AppLogger.logDebug(TAG, "setupBlocker: Loading count-based config")
                                countBasedConfig = Gson().fromJson<ReelCountConfig>(settings.reelBlockerConfig.settings,
                                    ReelCountConfig::class.java)
                                AppLogger.logVariable(TAG, "daily_reel_limit", getDailyReelCountLimit())
                                AppLogger.logDebug(TAG, "setupBlocker: Count-based config loaded")
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.functionError(TAG, "setupBlocker.settingsJob", e)
                }
            }

            val db = AppDatabase.getInstance(service)
            countJob = CoroutineScope(Dispatchers.IO).launch {
                try {
                    db.reelStatsDao().getCountFlow(TimeTools.getCurrentDate()).collectLatest { count ->
                        currentDailyCount = count ?: 0
                        AppLogger.logVariable(TAG, "current_daily_reel_count", currentDailyCount)
                    }
                } catch (e: Exception) {
                    AppLogger.functionError(TAG, "setupBlocker.countJob", e)
                }
            }
            
            AppLogger.logBlockerAction(TAG, "ReelBlocker", "Setup complete", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupBlocker", e)
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers(){
        AppLogger.logDebug(TAG, "setupReceivers() - Registering ReelBlocker broadcast receivers")
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
            AppLogger.logBlockerAction(TAG, "ReelBlocker", "Receivers registered", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupReceivers", e)
        }
    }

    fun removeReceivers(){
        AppLogger.logDebug(TAG, "removeReceivers() - Cleaning up ReelBlocker resources")
        try {
            service.unregisterReceiver(refreshReceiver)
            notificationManager.release()
            AppLogger.logBlockerAction(TAG, "ReelBlocker", "Receivers removed", "SUCCESS")
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
        val viewNode =
            findElementById(rootNode, viewId)
        val nodeRect = Rect()
        viewNode?.getBoundsInScreen(nodeRect)
        val isOffScreenLeft = nodeRect.right <= 0
        val isOffScreenRight = nodeRect.left >= screenWidth
        return (viewNode != null && !isOffScreenLeft && !isOffScreenRight)
    }

    private fun getDailyReelCountLimit(): Int? {
        val config = countBasedConfig ?: return null
        if (config.isDailyUniform) {
            return config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday, 1=Monday...
            return config.dailyLimits[dayOfWeek]
        }
    }

    /**
     * @return null if reels is not currently allowed by time config, or the end time in uptimeMillis if allowed.
     */
    private fun getEndTimeInMillis(): Long? {

        if(timeBAsedConfig==null) return null
        val calendar = Calendar.getInstance()
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(currentHour, currentMinute)

        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1 // 0=Sunday, 1=Monday...

        val intervals = if (timeBAsedConfig!!.isEveryday) {
            timeBAsedConfig!!.everydayIntervals
        } else {
            timeBAsedConfig!!.dailyIntervals[dayOfWeek] ?: emptyList()
        }

        intervals.forEach { interval ->
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) {
                    val remainingMins = endMinutes - currentMinutes
                    return SystemClock.uptimeMillis() + (remainingMins * 60 * 1000L)
                }
            } else {
                // cross midnight
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) {
                    val remainingMins = if (currentMinutes >= startMinutes) {
                        (1440 - currentMinutes) + endMinutes
                    } else {
                        endMinutes - currentMinutes
                    }
                    return SystemClock.uptimeMillis() + (remainingMins * 60 * 1000L)
                }
            }
        }
        return null
    }

}
