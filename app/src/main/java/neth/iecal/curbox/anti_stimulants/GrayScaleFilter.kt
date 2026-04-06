package neth.iecal.curbox.anti_stimulants

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.blockers.BaseBlocker
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.utils.GrayscaleControl
import neth.iecal.curbox.utils.getCurrentKeyboardPackageName
import java.util.Calendar

class GrayScaleFilter : BaseBlocker() {

    companion object {
        const val INTENT_ACTION_REFRESH_GRAYSCALE = "neth.iecal.curbox.refresh.grayscale"

        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

    }

    private lateinit var service: BaseBlockingService
    private val grayscaleControl = GrayscaleControl()
    private var ignoredGrayScalePackages: List<String> = listOf()
    private var lastPackageName: String? = null

    @Volatile
    private var grayscaleGroups: List<GrayscaleGroup> = emptyList()

    fun doGrayscaleCheck(event: AccessibilityEvent?) {
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val currentPackageName = event.packageName?.toString()
        if (currentPackageName == null || currentPackageName == lastPackageName || currentPackageName == service.packageName) return
        
        if (ignoredGrayScalePackages.contains(currentPackageName)) return

        lastPackageName = currentPackageName

        val now = Calendar.getInstance()
        val calDay = now.get(Calendar.DAY_OF_WEEK)
        val currentDay = if (calDay == Calendar.SUNDAY) 6 else calDay - 2
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        var shouldGrayscale = false

        for (group in grayscaleGroups) {
            if (!group.isActive) continue

            if (group.packages.contains(currentPackageName)) {
                val intervals = group.dailyIntervals[currentDay]
                if (intervals == null || intervals.isEmpty()) {
                    shouldGrayscale = true
                    break
                } else {
                    val isInInterval = intervals.any { isWithinInterval(currentMinutes, it) }
                    if (isInInterval) {
                        shouldGrayscale = true
                        break
                    }
                }
            }
        }

        if (shouldGrayscale) {
            AppLogger.logDebug("GrayScaleFilter", "Enabling monochrome for $currentPackageName")
            grayscaleControl.enableGrayscale()
        } else {
            grayscaleControl.disableGrayscale()
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

    fun setup(service: BaseBlockingService) {
        this.service = service
        ignoredGrayScalePackages = listOf(
            getCurrentKeyboardPackageName(service) ?: "com.google.android.inputmethod.latin"
        )
        
        CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                grayscaleGroups = settings.grayscaleGroups
                AppLogger.logDebug("GrayScaleFilter", "GrayScale Groups loaded: $grayscaleGroups")
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_GRAYSCALE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun unregisterReceivers() {
        try {
            service.unregisterReceiver(refreshReceiver)
        } catch (e: Exception) {
            // Ignored
        }
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_GRAYSCALE -> setup(service)
            }
        }
    }
}
