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
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
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
        const val INTENT_ACTION_REFRESH_APP_BLOCKER = "neth.iecal.curbox.refresh.appblocker"
        const val INTENT_ACTION_REFRESH_APP_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.appblocker.cooldown"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    }

    private val TAG = "AppBlocker"
    private var cooldownAppsList = ConcurrentHashMap<String, Long>()

    val blockedAppsList = ConcurrentHashMap<String, AppUsageConfig>()
    val timeBlockedAppsList = ConcurrentHashMap<String, AppTimeConfig>()
    private val onOpenAppsList = ConcurrentHashMap<String, Boolean>()
    private val appBlockerWarningScrnConfgs = ConcurrentHashMap<String, AppBlockerWarningScreenConfig>()

    private lateinit var usageStats : UsageStatsHelper
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private var settingsJob: kotlinx.coroutines.Job? = null

    private val handler = Handler(Looper.getMainLooper())
    private val activeRunnables = ConcurrentHashMap<String, Runnable>()
    private lateinit var notificationManager: TimerNotification

    fun doAppBlockerCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        if (packageName == service.packageName) {
            if (!service.isDelayOver(10000)) {
                showWarningScreen(packageName)
            }
            return
        }

        if (lastPackage == packageName || packageName == "com.android.systemui") return

        if (onOpenAppsList.containsKey(lastPackage) && lastPackage != packageName) {
            removeCooldownFrom(lastPackage)
        }

        lastPackage = packageName

        if (cooldownAppsList.containsKey(packageName)) {
            val endTime = cooldownAppsList[packageName]!!
            if (endTime < System.currentTimeMillis()) {
                removeCooldownFrom(packageName)
            } else {
                notificationManager.startTimer(totalMillis = endTime - System.currentTimeMillis(), timerId = packageName, title = "Remaining usage before lockdown")
                return
            }
        }

        if (onOpenAppsList.containsKey(packageName)) {
            notificationManager.stopTimer()
            showWarningScreen(packageName)
            return
        }

        if (timeBlockedAppsList.containsKey(packageName)) {
            if (isTimedBlockActive(packageName)) {
                notificationManager.stopTimer()
                showWarningScreen(packageName)
                return
            } else {
                val nextChange = getNextTimeStateChangeMillis(packageName)
                if (nextChange != null) {
                    setUpForcedRefreshChecker(packageName, nextChange)
                }
            }
        }

        if (blockedAppsList.containsKey(packageName)) {
            val config = blockedAppsList[packageName]!!
            val currentUsage = runBlocking { usageStats.getForegroundStatsByRelativeDay(0) }
                .firstOrNull { it.packageName == packageName }?.totalTime ?: 0L
            val usageLimitMillis = getUsageLimitForToday(config) * 60_000L
            val remainingUsage = usageLimitMillis - currentUsage

            if (remainingUsage <= 0) {
                notificationManager.stopTimer()
                showWarningScreen(packageName)
            } else {
                notificationManager.startTimer(
                    totalMillis = remainingUsage,
                    timerId = packageName,
                    title = service.getString(R.string.notification_title_remaining_usage)
                )
                setUpForcedRefreshChecker(packageName, System.currentTimeMillis() + remainingUsage)
                return
            }
        }

        notificationManager.stopTimer()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
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
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "setupReceivers", e)
        }
    }

    fun onDestroy() {
        service.unregisterReceiver(refreshReceiver)
        notificationManager.release()
        handler.removeCallbacksAndMessages(null)
        activeRunnables.clear()
        settingsJob?.cancel()
    }

    fun setupAppBlocker(service: BaseBlockingService) {
        this.service = service
        notificationManager = TimerNotification(service)
        usageStats = UsageStatsHelper(service)

        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val newBlockedAppsList = ConcurrentHashMap<String, AppUsageConfig>()
                val newTimeBlockedAppsList = ConcurrentHashMap<String, AppTimeConfig>()
                val newOnOpenAppsList = ConcurrentHashMap<String, Boolean>()
                val newWarningConfigs = ConcurrentHashMap<String, AppBlockerWarningScreenConfig>()
                
                // Load cooldowns from DataStore
                val currentCooldowns = ConcurrentHashMap<String, Long>()
                settings.appBlockerCooldowns.forEach { (packageName, endTime) ->
                    if (endTime > System.currentTimeMillis()) {
                        currentCooldowns[packageName] = endTime
                    }
                }
                cooldownAppsList = currentCooldowns

                settings.blockedAppGroups.forEach { group ->
                    if (!group.isActive) return@forEach
                    try {
                        when (group.blockingType) {
                            AppBlockingType.Usage -> {
                                val config = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                                group.selectedPackages.forEach {
                                    newBlockedAppsList[it.trim()] = config
                                    newWarningConfigs[it.trim()] = group.warningScreenConfig
                                }
                            }
                            AppBlockingType.Timed -> {
                                val config = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                                group.selectedPackages.forEach {
                                    newTimeBlockedAppsList[it.trim()] = config
                                    newWarningConfigs[it.trim()] = group.warningScreenConfig
                                }
                            }
                            AppBlockingType.OnOpen -> {
                                group.selectedPackages.forEach {
                                    newOnOpenAppsList[it.trim()] = true
                                    newWarningConfigs[it.trim()] = group.warningScreenConfig
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.functionError(TAG, "Error loading group ${group.name}", e)
                    }
                }
                
                blockedAppsList.clear()
                blockedAppsList.putAll(newBlockedAppsList)
                timeBlockedAppsList.clear()
                timeBlockedAppsList.putAll(newTimeBlockedAppsList)
                onOpenAppsList.clear()
                onOpenAppsList.putAll(newOnOpenAppsList)
                appBlockerWarningScrnConfgs.clear()
                appBlockerWarningScrnConfgs.putAll(newWarningConfigs)

                handler.post {
                    try {
                        val currentPackage = service.rootInActiveWindow?.packageName?.toString()
                        if (currentPackage != null) {
                            lastPackage = "" 
                            val dummyEvent = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
                            dummyEvent.packageName = currentPackage
                            doAppBlockerCheck(dummyEvent)
                            dummyEvent.recycle()
                        }
                    } catch (e: Exception) {
                        AppLogger.functionError(TAG, "Error in forced re-check", e)
                    }
                }
            }
        }
    }

    private fun handlePutCooldownIntentBroadcast(intent: Intent) {
        try {
            val coolPackage = intent.getStringExtra("result_id") ?: return
            val durationMillis = intent.getIntExtra(
                "selected_time",
                appBlockerWarningScrnConfgs[coolPackage]?.timeInterval ?: 10
            )
            val realTimeEndMillis = System.currentTimeMillis() + durationMillis
            notificationManager.startTimer(totalMillis = durationMillis.toLong(), timerId = coolPackage, title = "Remaining usage before lockdown")
            putCooldownTo(coolPackage, realTimeEndMillis)
            setUpForcedRefreshChecker(coolPackage, realTimeEndMillis)
        } catch (e: Exception) {
            AppLogger.functionError(TAG, "handlePutCooldownIntentBroadcast", e)
        }
    }

    private fun getUsageLimitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) config.uniformLimit
        else {
            val calendar = Calendar.getInstance()
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
            config.dailyLimits[dayOfWeek]
        }
    }

    private fun persistCooldownData() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                service.dataStoreManager.updateAppBlockerCooldowns(cooldownAppsList.toMap())
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

    private fun isTimedBlockActive(packageName: String): Boolean {
        val config = timeBlockedAppsList[packageName] ?: return false
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[dayOfWeek] ?: emptyList()

        if (intervals.isEmpty()) return true

        for (interval in intervals) {
            val startMinutes = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val endMinutes = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            if (startMinutes <= endMinutes) {
                if (currentMinutes in startMinutes until endMinutes) return false
            } else {
                if (currentMinutes >= startMinutes || currentMinutes < endMinutes) return false
            }
        }
        return true
    }

    private fun getNextTimeStateChangeMillis(packageName: String): Long? {
        val config = timeBlockedAppsList[packageName] ?: return null
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals else config.dailyIntervals[dayOfWeek] ?: emptyList()

        if (intervals.isEmpty()) return null

        var minMinutesUntilChange = Int.MAX_VALUE
        for (interval in intervals) {
            val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)

            val minutesUntilChange = if (start <= end) {
                if (currentMinutes in start until end) end - currentMinutes
                else if (currentMinutes < start) start - currentMinutes
                else (1440 - currentMinutes) + start
            } else {
                if (currentMinutes >= start || currentMinutes < end) {
                    if (currentMinutes >= start) (1440 - currentMinutes) + end
                    else end - currentMinutes
                } else start - currentMinutes
            }
            minMinutesUntilChange = minOf(minMinutesUntilChange, minutesUntilChange)
        }

        return System.currentTimeMillis() + (minMinutesUntilChange * 60_000L) - 
               (calendar.get(Calendar.SECOND) * 1000L) - calendar.get(Calendar.MILLISECOND)
    }

    private fun setUpForcedRefreshChecker(coolPackage: String, realTimeEndMillis: Long) {
        activeRunnables[coolPackage]?.let { handler.removeCallbacks(it) }
        val delayMillis = realTimeEndMillis - System.currentTimeMillis()
        if (delayMillis <= 0) return

        val runnable = Runnable {
            try {
                if (service.rootInActiveWindow?.packageName == coolPackage) {
                    removeCooldownFrom(coolPackage)
                    showWarningScreen(coolPackage)
                    lastPackage = ""
                }
            } catch (e: Exception) {
                AppLogger.functionError("AppBlocker", "setUpForcedRefreshChecker recheck", e)
                setUpForcedRefreshChecker(coolPackage, System.currentTimeMillis() + 60_000L)
            } finally {
                activeRunnables.remove(coolPackage)
            }
        }
        activeRunnables[coolPackage] = runnable
        handler.postDelayed(runnable, delayMillis)
    }

    private fun showWarningScreen(packageName: String) {
        if (service.isDelayOver(10000)) {
            notificationManager.stopTimer()
            service.pressHome()
            lastPackage = ""

            try {
                if (AppSuspendHelper.isShizukuAvailable() && packageName != service.packageName) {
                    ShizukuRunner.executeCommand(
                        "am force-stop $packageName",
                        object : ShizukuRunner.CommandResultListener {})
                }
            } catch (e: Exception) {
                Log.e("AppBlocker", "Shizuku force-stop failed", e)
            }

            if (appBlockerWarningScrnConfgs[packageName]?.isWarningDialogHidden == true) return

            handler.postDelayed({
                val config = appBlockerWarningScrnConfgs[packageName] ?: AppBlockerWarningScreenConfig(
                    message = "Wait a moment before opening Curbox right after a block.",
                    proceedDelayInSecs = 5
                )
                val dialogIntent = Intent(service, WarningActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("mode", Constants.WARNING_SCREEN_MODE_APP_BLOCKER)
                    putExtra("result_id", packageName)
                    putExtra("warning_config", Gson().toJson(config))
                }
                service.startActivity(dialogIntent)
            }, 100)
        }
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
