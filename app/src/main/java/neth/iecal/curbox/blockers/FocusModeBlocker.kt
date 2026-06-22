package neth.iecal.curbox.blockers

import neth.iecal.curbox.R

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.AutoFocusGroup
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AppSuspendHelper
import neth.iecal.curbox.utils.TimerNotification
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.util.Calendar

class FocusModeBlocker : BaseBlocker() {

    private data class ManualFocusModeData(
        val focusGroupData: ManualFocusGroup,
        val endTimeInMillis: Long
    )

    companion object {
        const val INTENT_ACTION_REFRESH_FOCUS_MODE = "neth.iecal.curbox.refresh.focus_mode"
        const val INTENT_ACTION_EXIT_AUTO_FOCUS = "neth.iecal.curbox.exit.auto_focus"
        const val INTENT_ACTION_UNSUSPEND_ALL = "neth.iecal.curbox.unsuspend_all_apps"
        private const val AUTO_FOCUS_NOTIFICATION_ID = 2001
        private const val AUTO_FOCUS_CHANNEL_ID = "AutoFocusChannel"
    }

    private var focusModeData: ManualFocusModeData? = null
    private var lastPackage = ""
    private lateinit var service: BaseBlockingService
    private lateinit var notificationManager: TimerNotification

    private var autoFocusGroups: List<AutoFocusGroup> = emptyList()
    private var manualFocusGroups: List<ManualFocusGroup> = emptyList()
    private val dismissedAutoFocusGroupIds = mutableSetOf<String>()
    private var autoFocusNotificationShown = false
    private var essentialPackages: Set<String> = emptySet()
    private var currentActiveAutoFocusGroupId: String? = null

    private var currentlySuspendedPackages = setOf<String>()
    private var lastEvaluatedMinute = -1

    private fun updateSuspendedPackages(serviceContext: Context) {
        val newSuspendedPackages = mutableSetOf<String>()

                var shouldDndBeOn = false
        focusModeData?.focusGroupData?.let { group ->
            if (group.autoTurnOnDnd) shouldDndBeOn = true
            newSuspendedPackages.addAll(
                AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
            )
        }

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (group in autoFocusGroups) {
            if (dismissedAutoFocusGroupIds.contains(group.groupId)) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
                        if (isInInterval) {
                if (group.autoTurnOnDnd) shouldDndBeOn = true
                newSuspendedPackages.addAll(
                    AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
                )
            }
        }

        // Also check manual focus groups with recurring schedules
        for (group in manualFocusGroups) {
            if (!group.isRecurring) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
            if (isInInterval) {
                if (group.autoTurnOnDnd) shouldDndBeOn = true
                newSuspendedPackages.addAll(
                    AppSuspendHelper.getPackagesToSuspend(serviceContext, group.blockMode, group.packages, essentialPackages)
                )
            }
        }

        val toSuspend = newSuspendedPackages - currentlySuspendedPackages
        val toUnsuspend = currentlySuspendedPackages - newSuspendedPackages

        if (toSuspend.isNotEmpty()) {
            AppSuspendHelper.suspendApps(toSuspend.toList())
        }
        if (toUnsuspend.isNotEmpty()) {
            AppSuspendHelper.unsuspendApps(toUnsuspend.toList())
        }

        currentlySuspendedPackages = newSuspendedPackages
        
        applyDndState(serviceContext, shouldDndBeOn)
    }

    private var wasDndTurnedOnByUs = false

    private fun applyDndState(context: Context, shouldBeOn: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        
        val currentFilter = nm.currentInterruptionFilter
        if (shouldBeOn) {
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                wasDndTurnedOnByUs = true
            }
        } else {
            if (wasDndTurnedOnByUs && currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                wasDndTurnedOnByUs = false
            }
        }
    }

    private fun turnOffFocusMode() {
        val groupId = focusModeData?.focusGroupData?.groupId
        focusModeData = null
        CoroutineScope(Dispatchers.IO).launch {
            if (groupId != null) {
                val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
                val statsDao = db.focusStatsDao()
                val runningSessions = statsDao.getRunningSessions().filter { !it.wasAutoFocus && it.groupId == groupId }
                for (session in runningSessions) {
                    statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
            service.dataStoreManager.setManualFocusStateToInactive()
        }
        notificationManager.stopTimer()
        updateSuspendedPackages(service)
    }

    fun doFocusModeCheck(event: AccessibilityEvent?) {
        val packageName = event?.packageName.toString()
        if (lastPackage == packageName || packageName == service.getPackageName()) return
        lastPackage = packageName

        fun performBlock() {
            service.pressHome()
            lastPackage = ""
            Toast.makeText(service, service.getString(R.string.this_app_is_currently_under_focus), Toast.LENGTH_LONG).show()
        }

        if (focusModeData != null) {
            when (focusModeData!!.focusGroupData.blockMode) {
                FocusBlockMode.BLOCK_SELECTED -> {
                    if (focusModeData!!.focusGroupData.packages.contains(packageName)) performBlock()
                }
                FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED -> {
                    if (!focusModeData!!.focusGroupData.packages.contains(packageName)) performBlock()
                }
            }
            if (focusModeData!!.endTimeInMillis < System.currentTimeMillis()) {
                turnOffFocusMode()
            }
        }

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        // UI saves: 0=Mon,1=Tue,...,6=Sun. Calendar: 1=Sun,2=Mon,...,7=Sat
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        
        if (currentMinutes != lastEvaluatedMinute) {
            updateSuspendedPackages(service)
            lastEvaluatedMinute = currentMinutes
        }
        
        var anyAutoFocusActive = false
        var activeAutoFocusGroupId: String? = null

        for (group in autoFocusGroups) {
            if (dismissedAutoFocusGroupIds.contains(group.groupId)) continue
            val intervals = group.dailyIntervals[currentDay] ?: continue
            val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
            if (!isInInterval) continue

            anyAutoFocusActive = true
            activeAutoFocusGroupId = group.groupId
            val blocked = when (group.blockMode) {
                FocusBlockMode.BLOCK_SELECTED -> group.packages.contains(packageName)
                FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED ->
                    !group.packages.contains(packageName) && !essentialPackages.contains(packageName)
            }
            if (blocked) {
                performBlock()
                break
            }
        }

        if (anyAutoFocusActive && !autoFocusNotificationShown) {
            showAutoFocusNotification(activeAutoFocusGroupId!!)
        } else if (!anyAutoFocusActive && autoFocusNotificationShown) {
            hideAutoFocusNotification()
            dismissedAutoFocusGroupIds.clear()
        }
    }

    private fun isWithinInterval(currentMinutes: Int, interval: TimeInterval): Boolean {
        val start = interval.startHour * 60 + interval.startMinute
        val end = interval.endHour * 60 + interval.endMinute
        return if (start <= end) {
            currentMinutes in start until end
        } else {
            currentMinutes >= start || currentMinutes < end
        }
    }

    private fun showAutoFocusNotification(groupId: String) {
        autoFocusNotificationShown = true
        currentActiveAutoFocusGroupId = groupId
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId == groupId }
            if (runningSessions.isEmpty()) {
                val session = neth.iecal.curbox.data.db.FocusStatsEntity(
                    groupId = groupId,
                    wasAutoFocus = true,
                    startTimeInMillis = System.currentTimeMillis(),
                    estimatedEndTimeInMillis = System.currentTimeMillis(),
                    actualEndTimeInMillis = 0L,
                    status = 0
                )
                statsDao.insert(session)
            }
        }

        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val hasExitable = autoFocusGroups.any { it.exitable && !dismissedAutoFocusGroupIds.contains(it.groupId) }

        val builder = NotificationCompat.Builder(service, AUTO_FOCUS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Auto Focus is active")
            .setContentText("Scheduled focus mode is running")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)

        if (hasExitable) {
            val exitIntent = Intent(INTENT_ACTION_EXIT_AUTO_FOCUS)
            val pendingIntent = PendingIntent.getBroadcast(
                service, 0, exitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(android.R.drawable.ic_delete, "Stop", pendingIntent)
        }

        nm.notify(AUTO_FOCUS_NOTIFICATION_ID, builder.build())
    }

    private fun hideAutoFocusNotification(wasForceStopped: Boolean = false, targetGroupId: String? = null) {
        autoFocusNotificationShown = false
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(AUTO_FOCUS_NOTIFICATION_ID)
        
        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            
            val runningSessions = if (wasForceStopped && targetGroupId != null) {
                statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId == targetGroupId }
            } else if (wasForceStopped) {
                val exitableIds = autoFocusGroups.filter { it.exitable }.map { it.groupId }
                statsDao.getRunningSessions().filter { it.wasAutoFocus && it.groupId in exitableIds }
            } else {
                statsDao.getRunningSessions().filter { it.wasAutoFocus && (currentActiveAutoFocusGroupId == null || it.groupId == currentActiveAutoFocusGroupId) }
            }
            
            val now = System.currentTimeMillis()
            for (session in runningSessions) {
                val newStatus = if (wasForceStopped) 2 else 1
                statsDao.update(session.copy(status = newStatus, actualEndTimeInMillis = now))
            }
            currentActiveAutoFocusGroupId = null
        }
    }

    private fun createAutoFocusNotificationChannel() {
        val nm = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            AUTO_FOCUS_CHANNEL_ID, "Auto Focus", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Auto focus mode notifications"
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_FOCUS_MODE)
            addAction(INTENT_ACTION_EXIT_AUTO_FOCUS)
            addAction(INTENT_ACTION_UNSUSPEND_ALL)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
    }

    fun setupFocusMode(service: BaseBlockingService) {
        this.service = service
        notificationManager = TimerNotification(service)
        createAutoFocusNotificationChannel()

        // cache essential packages
        val essential = mutableSetOf("com.android.systemui")
        getDefaultLauncherPackageName(service.packageManager)?.let { essential.add(it) }
        getCurrentKeyboardPackageName(service)?.let { essential.add(it) }
        essentialPackages = essential

        CoroutineScope(Dispatchers.IO).launch {
            val db = neth.iecal.curbox.data.db.AppDatabase.getInstance(service)
            val statsDao = db.focusStatsDao()
            val runningSessions = statsDao.getRunningSessions()
            for (session in runningSessions) {
                if (!session.wasAutoFocus && session.estimatedEndTimeInMillis < System.currentTimeMillis()) {
                     statsDao.update(session.copy(status = 1, actualEndTimeInMillis = session.estimatedEndTimeInMillis))
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->

                if (settings.activeManualFocusGroupId.first != null) {
                    val currentFocusingGroup = settings.manualFocusGroups.find { it.groupId == settings.activeManualFocusGroupId.first }
                    if (currentFocusingGroup != null && settings.activeManualFocusGroupId.second > System.currentTimeMillis()) {
                        if (currentFocusingGroup.blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) {
                            currentFocusingGroup.packages.addAll(essentialPackages)
                        }
                        focusModeData = ManualFocusModeData(currentFocusingGroup, settings.activeManualFocusGroupId.second)
                        withContext(Dispatchers.Main) {
                            notificationManager.startTimer(
                                focusModeData!!.endTimeInMillis - System.currentTimeMillis(),
                                timerId = "focus_mode",
                                title = "Focus Mode is on"
                            )
                        }
                    }
                } else {
                    focusModeData = null
                    withContext(Dispatchers.Main) {
                        notificationManager.stopTimer()
                    }
                }

                autoFocusGroups = settings.autoFocusGroups
                manualFocusGroups = settings.manualFocusGroups
                dismissedAutoFocusGroupIds.clear()
                updateSuspendedPackages(service)
            }
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_FOCUS_MODE -> setupFocusMode(service)
                INTENT_ACTION_EXIT_AUTO_FOCUS -> {
                    val specificGroupId = intent.getStringExtra("group_id")
                    if (specificGroupId != null) {
                        dismissedAutoFocusGroupIds.add(specificGroupId)
                        hideAutoFocusNotification(wasForceStopped = true, targetGroupId = specificGroupId)
                    } else {
                        autoFocusGroups.filter { it.exitable }.forEach {
                            dismissedAutoFocusGroupIds.add(it.groupId)
                        }
                        hideAutoFocusNotification(wasForceStopped = true)
                    }
                    lastPackage = ""
                    updateSuspendedPackages(service)
                }
                INTENT_ACTION_UNSUSPEND_ALL -> {
                    AppSuspendHelper.unsuspendAllApps(context ?: service)
                }
            }
        }
    }
}