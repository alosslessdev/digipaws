package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.ShizukuRunner
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.AppLogger
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

class AppBlocker() : BaseBlocker() {

    companion object {
        /**
         * Refreshes information about warning screen, cheat hours and blocked app list
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"

        /**
         * Add cooldown to an app.
         * This broadcast should always be sent together with the following keys:
         * selected_time: Int -> Duration of cooldown in millis
         * result_id : String -> Package name of app to be put into cooldown
         */
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private val TAG = "AppBlocker"
        /**
     * stores what blocked apps have been allowed by the user to be used and until when
     * package-name -> end-time-in-real-time-millis
     */
    private var cooldownAppsList = HashMap<String, Long>()

    /**
     * Stores general simple general list of block apps with their configs
     */
    var blockedAppsList = HashMap<String, AppUsageConfig>()
    var timeBlockedAppsList = HashMap<String, AppTimeConfig>()
    private var appBlockerWarningScrnConfgs = HashMap<String, AppBlockerWarningScreenConfig>()

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService


    // responsible to trigger a recheck for what app user is currently using even when no event is received. Used in putting the usage recheck logic into
    // cooldown for an app and later when the cooldown duration is over, trigger a recheck
    private val handler = Handler(Looper.getMainLooper())

    private val activeRunnables = HashMap<String, Runnable>()

    private lateinit var notificationManager: TimerNotification


    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) {
            AppLogger.logDebug(TAG, "doAppBlockerCheck: Ignoring event - event is null or wrong type")
            return
        }

        val packageName = event.packageName?.toString() ?: return
        
        if (lastPackage == packageName || packageName == service.packageName || packageName == "com.android.systemui") {
            AppLogger.logDebug(TAG, "doAppBlockerCheck: Ignoring same package or system UI: $packageName")
            return
        }

        lastPackage = packageName
        AppLogger.logAccessibilityEvent(TAG, "APP_BLOCKER_CHECK", packageName)

        // Check Cooldown
        if (cooldownAppsList.containsKey(packageName)) {
            val cooldownEndTime = cooldownAppsList[packageName]!!
            val currentTime = System.currentTimeMillis()
            AppLogger.logVariable(TAG, "cooldown_end_time", cooldownEndTime)
            AppLogger.logVariable(TAG, "current_time", currentTime)
            
            if (cooldownEndTime < currentTime) {
                AppLogger.logBlockerAction(TAG, "AppBlocker", "Removing cooldown from $packageName")
                removeCooldownFrom(packageName)
            } else {
                val remainingTime = cooldownEndTime - currentTime
                AppLogger.logBlockerAction(TAG, "AppBlocker", "App in cooldown", "remaining=${remainingTime}ms, showing timer")
                notificationManager.startTimer(totalMillis = remainingTime, timerId = packageName, title = "Remaining usage before lockdown")
                return // Still in cooldown, let them use it
            }
        }
        
        // Check Time Blocks
        if (timeBlockedAppsList.contains(packageName)) {
            AppLogger.logDebug(TAG, "doAppBlockerCheck: $packageName is time-blocked")
            val endAllowedRealTime = getEndTimeInRealTimeMillis(packageName)
            if (endAllowedRealTime == null) {
                AppLogger.logBlockerAction(TAG, "AppBlocker", "Time-blocked app blocked", "package=$packageName")
                notificationManager.stopTimer()
                showWarningScreen(packageName)
                return
            } else {
                AppLogger.logBlockerAction(TAG, "AppBlocker", "Time-blocked app - setting refresh", "endTime=$endAllowedRealTime")
                setUpForcedRefreshChecker(packageName, endAllowedRealTime)
            }
        }

        // Check Usage Blocks
        if (blockedAppsList.contains(packageName)) {
            AppLogger.logDebug(TAG, "doAppBlockerCheck: $packageName is usage-blocked")
            val config = blockedAppsList[packageName]!!
            val currentUsage = usageStats.getForegroundStatsByRelativeDay(0)
                .firstOrNull { it.packageName == packageName }?.totalTime ?: 0L
            val usageLimitMillis = getUsageLimitForToday(config) * 60_000L
            val remainingUsage = usageLimitMillis - currentUsage
            
            AppLogger.logVariable(TAG, "current_usage_ms", currentUsage)
            AppLogger.logVariable(TAG, "usage_limit_ms", usageLimitMillis)
            AppLogger.logVariable(TAG, "remaining_usage_ms", remainingUsage)

            if (remainingUsage <= 0) {
                AppLogger.logBlockerAction(TAG, "AppBlocker", "Usage limit reached for $packageName", "remaining=${remainingUsage}ms")
                notificationManager.stopTimer()
                showWarningScreen(packageName)
            } else {
                AppLogger.logBlockerAction(TAG, "AppBlocker", "Usage under limit, showing timer", "remaining=${remainingUsage}ms")
                notificationManager.startTimer(totalMillis = remainingUsage, timerId = packageName, title = "Remaining usage before lockdown")
                setUpForcedRefreshChecker(packageName, System.currentTimeMillis() + remainingUsage)
                return
            }
        }

        AppLogger.logDebug(TAG, "doAppBlockerCheck: $packageName - no blocks applied")
        notificationManager.stopTimer()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        AppLogger.logDebug(TAG, "setupReceivers() - Registering AppBlocker broadcast receivers")
        try {
            val filter = IntentFilter().apply {
                addAction(INTENT_ACTION_REFRESH_APP_BLOCKER)
                addAction(INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(refreshReceiver, filter)
            }
            AppLogger.logBlockerAction(TAG, "AppBlocker", "Receivers registered", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupReceivers", e)
        }
    }

    fun onDestroy() {
        AppLogger.logDebug(TAG, "onDestroy() - Cleaning up AppBlocker resources")
        try {
            service.unregisterReceiver(refreshReceiver)
            notificationManager.release()
            handler.removeCallbacksAndMessages(null)
            activeRunnables.clear()
            AppLogger.logBlockerAction(TAG, "AppBlocker", "Cleanup complete", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "onDestroy", e)
        }
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        AppLogger.logBlockerAction(TAG, "AppBlocker", "Setup started")
        try {
            this.service = service
            AppLogger.logDebug(TAG, "setupAppBlocker: Service assigned")
            
            notificationManager = TimerNotification(service)
            AppLogger.logDebug(TAG, "setupAppBlocker: NotificationManager created")
            
            usageStats = UsageStatsHelper(service)
            AppLogger.logDebug(TAG, "setupAppBlocker: UsageStatsHelper created")
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    service.dataStoreManager.settings.collectLatest { settings ->
                        AppLogger.logDebug(TAG, "setupAppBlocker: Settings updated from DataStore")
                        // Clear existing thread-safe maps and repopulate them
                        blockedAppsList.clear()
                        timeBlockedAppsList.clear()
                        appBlockerWarningScrnConfgs.clear()
                        cooldownAppsList.clear()

                        // Load cooldowns from DataStore
                        settings.appBlockerCooldowns.forEach { (packageName, endTime) ->
                            if (endTime > System.currentTimeMillis()) {
                                cooldownAppsList[packageName] = endTime
                            }
                        }

                        var usageBlockedCount = 0
                        var timeBlockedCount = 0
                        
                        settings.blockedAppGroups.forEach { group ->
                            if (!group.isActive) {
                                AppLogger.logDebug(TAG, "setupAppBlocker: Skipping inactive group ${group.name}")
                                return@forEach
                            }
                            
                            if (group.blockingType == AppBlockingType.Usage) {
                                val appUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                                group.selectedPackages.forEach {
                                    blockedAppsList[it] = appUsageConfig
                                    appBlockerWarningScrnConfgs[it] = group.warningScreenConfig
                                    usageBlockedCount++
                                    AppLogger.logDebug(TAG, "setupAppBlocker: Added usage-blocked app: $it")
                                }
                            } else {
                                val appTimedConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                                group.selectedPackages.forEach {
                                    timeBlockedAppsList[it] = appTimedConfig
                                    appBlockerWarningScrnConfgs[it] = group.warningScreenConfig
                                    timeBlockedCount++
                                    AppLogger.logDebug(TAG, "setupAppBlocker: Added time-blocked app: $it")
                                }
                            }
                        }
                        
                        AppLogger.logVariable(TAG, "usage_blocked_apps_count", usageBlockedCount)
                        AppLogger.logVariable(TAG, "time_blocked_apps_count", timeBlockedCount)
                        AppLogger.logBlockerAction(TAG, "AppBlocker", "Settings loaded", "usage=$usageBlockedCount, time=$timeBlockedCount")
                    }
                } catch (e: Exception) {
                    AppLogger.functionError(TAG, "setupAppBlocker.collectLatest", e)
                }
            }
            
            AppLogger.logBlockerAction(TAG, "AppBlocker", "Setup complete", "SUCCESS")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupAppBlocker", e)
        }
    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        AppLogger.functionEntry(TAG, "handlePutCooldownIntentBroadcast")
        try {
            val coolPackage = intent.getStringExtra("result_id") ?: run {
                AppLogger.logWarn(TAG, "handlePutCooldownIntentBroadcast: No result_id in intent")
                return
            }

            val durationMillis = intent.getIntExtra(
                "selected_time",
                appBlockerWarningScrnConfgs[coolPackage]?.timeInterval ?: 10
            )
            AppLogger.logVariable(TAG, "cooldown_package", coolPackage)
            AppLogger.logVariable(TAG, "cooldown_duration_ms", durationMillis)
            
            val realTimeEndMillis = System.currentTimeMillis() + durationMillis
            AppLogger.logVariable(TAG, "cooldown_end_time_ms", realTimeEndMillis)

            AppLogger.logBlockerAction(TAG, "AppBlocker", "Applying cooldown to $coolPackage", "duration=${durationMillis}ms")
            notificationManager.startTimer(totalMillis = durationMillis.toLong(), timerId = coolPackage, title = "Remaining usage before lockdown")

            putCooldownTo(coolPackage, realTimeEndMillis)
            setUpForcedRefreshChecker(coolPackage, realTimeEndMillis)
            
            AppLogger.functionExit(TAG, "handlePutCooldownIntentBroadcast")
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "handlePutCooldownIntentBroadcast", e)
        }
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) {
            config.uniformLimit
        } else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun persistCooldownData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.dataStoreManager.updateAppBlockerCooldowns(cooldownAppsList.toMap())
                AppLogger.logDebug(TAG, "Cooldown data persisted to DataStore")
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "persistCooldownData", e)
            }
        }
    }

    private fun putCooldownTo(packageName: String, realTimeEnd: Long) {
        cooldownAppsList[packageName] = realTimeEnd
        persistCooldownData()

    }

    private fun removeCooldownFrom(packageName: String) {
        cooldownAppsList.remove(packageName)
        persistCooldownData()
    }

    private fun getEndTimeInRealTimeMillis(packageName: String): Long? {
        val config = timeBlockedAppsList[packageName] ?: return null
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1

        Log.d("day of week", dayOfWeek.toString())
        val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[dayOfWeek] ?: emptyList()

        intervals.forEach { interval ->
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) {
                    val remainingMins = endMinutes - currentMinutes
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            } else {
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) {
                    val remainingMins = if (currentMinutes >= startMinutes) {
                        (1440 - currentMinutes) + endMinutes
                    } else {
                        endMinutes - currentMinutes
                    }
                    return System.currentTimeMillis() + (remainingMins * 60_000L)
                }
            }
        }
        return null
    }

    private fun setUpForcedRefreshChecker(coolPackage: String, realTimeEndMillis: Long) {
        // Cancel any existing timer for THIS specific package
        activeRunnables[coolPackage]?.let { handler.removeCallbacks(it) }

        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return // Time is already up

        val runnable = Runnable {
            try {
                if (service.rootInActiveWindow?.packageName == coolPackage) {
                    removeCooldownFrom(coolPackage)
                    showWarningScreen(coolPackage)
                    lastPackage = ""
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Recheck error: $e")
                // Retry in 1 minute if UI check failed
                setUpForcedRefreshChecker(coolPackage, System.currentTimeMillis() + 60_000L)
            } finally {
                activeRunnables.remove(coolPackage) // Clean up memory
            }
        }

        activeRunnables[coolPackage] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String) {
        notificationManager.stopTimer()
        service.pressHome()
        lastPackage = ""

        if (AppSuspendHelper.isShizukuAvailable()) {
            ShizukuRunner.executeCommand("am force-stop $packageName", object : ShizukuRunner.CommandResultListener {})
        }

        if (appBlockerWarningScrnConfgs[packageName]?.isWarningDialogHidden == true) return

        handler.postDelayed({
            val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                putExtra("result_id", packageName)
                putExtra("warning_config", Gson().toJson(appBlockerWarningScrnConfgs[packageName]))
            }
            service.startActivity(dialogIntent)
        }, 300)
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_APP_BLOCKER -> setupAppBlocker(service)
                INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN -> handlePutCooldownIntentBroadcast(intent)
            }
        }
    }
}