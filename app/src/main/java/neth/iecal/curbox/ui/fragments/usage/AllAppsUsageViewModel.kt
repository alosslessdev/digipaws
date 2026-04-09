package neth.iecal.curbox.ui.fragments.usage

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.ui.views.WeeklyBarGraphView
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName
import java.util.concurrent.ConcurrentHashMap
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

class AllAppsUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val packageManager = application.packageManager

    val ignoredPackages: MutableSet<String> = mutableSetOf()

    private val dayStatsCache = ConcurrentHashMap<LocalDate, List<AllAppsUsageFragment.Stat>>()
    private val appMetadataCache = ConcurrentHashMap<String, AppMetadata>()
    private val snapshotMetadataCache = ConcurrentHashMap<String, AppMetadata>()

    data class AppMetadata(
        val label: CharSequence,
        val category: String,
        val isSystemApp: Boolean,
        val installDate: String,
        val lastUpdate: String,
        val icon: Drawable?
    )

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    // Current week offset: 0 = current week, -1 = last week, etc.
    private val _weekOffset = MutableLiveData(0)
    val weekOffset: LiveData<Int> = _weekOffset

    // Week range label like "Mar 10 – Mar 16"
    private val _weekRangeLabel = MutableLiveData<String>()
    val weekRangeLabel: LiveData<String> = _weekRangeLabel

    // Weekly bar data (7 entries)
    private val _weeklyData = MutableLiveData<List<WeeklyBarGraphView.DayData>>()
    val weeklyData: LiveData<List<WeeklyBarGraphView.DayData>> = _weeklyData

    // Selected day index within the week (0-6)
    private val _selectedDayIndex = MutableLiveData(6) // default to last day (Sunday) or today
    val selectedDayIndex: LiveData<Int> = _selectedDayIndex

    // Stats for the selected day
    private val _selectedDayStats = MutableLiveData<List<AllAppsUsageFragment.Stat>>()
    val selectedDayStats: LiveData<List<AllAppsUsageFragment.Stat>> = _selectedDayStats

    // Total usage time in millis for selected day
    private val _totalTime = MutableLiveData<Long>(0L)
    val totalTime: LiveData<Long> = _totalTime

    // Comparison text
    private val _comparisonText = MutableLiveData<String>()
    val comparisonText: LiveData<String> = _comparisonText

    // Date sublabel ("TOTAL TODAY" or "TOTAL · Mar 15")
    private val _dateSublabel = MutableLiveData("TOTAL TODAY")
    val dateSublabel: LiveData<String> = _dateSublabel

    // Can navigate forward?
    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            getDefaultLauncherPackageName(getApplication<Application>().packageManager)?.let {
                ignoredPackages.add(it)
            }
            val datastore = neth.iecal.curbox.utils.DataStoreManager(getApplication())
            ignoredPackages.addAll(datastore.settings.first().usageTrackerIgnoredApps)
            invalidateDynamicDayCache()
            loadWeekData()
        }
    }

    fun goToPreviousWeek() {
        _weekOffset.value = (_weekOffset.value ?: 0) - 1
        viewModelScope.launch(Dispatchers.IO) {
            loadWeekData()
        }
    }

    fun goToNextWeek() {
        val current = _weekOffset.value ?: 0
        if (current < 0) {
            _weekOffset.value = current + 1
            viewModelScope.launch(Dispatchers.IO) {
                loadWeekData()
            }
        }
    }

    fun selectDay(index: Int) {
        _selectedDayIndex.value = index
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) { _isLoading.value = true }
            val weekStart = getWeekStart(_weekOffset.value ?: 0)
            val selectedDate = weekStart.plusDays(index.toLong())
            loadDayStats(selectedDate)
            computeComparison(selectedDate)
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            invalidateDynamicDayCache()
            loadWeekData()
        }
    }

    private suspend fun loadWeekData() {
        withContext(Dispatchers.Main) { _isLoading.value = true }

        val offset = withContext(Dispatchers.Main) { _weekOffset.value ?: 0 }
        val weekStart = getWeekStart(offset)
        val weekEnd = weekStart.plusDays(6)

        val today = LocalDate.now()
        val isCurrentWeek = offset == 0

        withContext(Dispatchers.Main) {
            _canGoNext.value = offset < 0

            val startLabel = weekStart.format(dayLabelFormatter)
            val endLabel = weekEnd.format(dayLabelFormatter)
            _weekRangeLabel.value = "$startLabel – $endLabel"
        }

        val dayDataList = mutableListOf<WeeklyBarGraphView.DayData>()
        val dayLabels = listOf("M", "T", "W", "T", "F", "S", "S")

        var todayIndex = -1

        for (i in 0..6) {
            val date = weekStart.plusDays(i.toLong())
            val isFuture = date.isAfter(today)

            val totalTimeMs = if (isFuture) {
                0L
            } else {
                getFilteredStatsForDay(date).sumOf { it.totalTime }
            }

            val hours = totalTimeMs / (1000f * 60f * 60f)
            val dateMillis = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

            dayDataList.add(WeeklyBarGraphView.DayData(dayLabels[i], hours, dateMillis))

            if (date == today) todayIndex = i
        }

        // Choose the selected day: today if in this week, else last day of the week
        val defaultSelected = if (isCurrentWeek && todayIndex >= 0) todayIndex else 6

        withContext(Dispatchers.Main) {
            _weeklyData.value = dayDataList
            _selectedDayIndex.value = defaultSelected
        }

        // Load stats for the selected day
        val selectedDate = weekStart.plusDays(defaultSelected.toLong())
        loadDayStats(selectedDate)

        // Compute comparison text
        computeComparison(selectedDate)
    }

    private suspend fun loadDayStats(date: LocalDate) {
        val stats = getFilteredStatsForDay(date)
        rememberSnapshotMetadata(stats)
        preloadAppMetadata(stats.map { it.packageName })
        val total = stats.sumOf { it.totalTime }
        val today = LocalDate.now()
        val isToday = date == today

        val sublabel = if (isToday) {
            "TOTAL TODAY"
        } else {
            "TOTAL · ${date.format(dayLabelFormatter)}"
        }

        withContext(Dispatchers.Main) {
            _selectedDayStats.value = stats
            _totalTime.value = total
            _dateSublabel.value = sublabel
        }
    }

    private suspend fun computeComparison(selectedDate: LocalDate) {
        val today = LocalDate.now()
        val isToday = selectedDate == today

        val previousDay = selectedDate.minusDays(1)
        val previousDayStats = getFilteredStatsForDay(previousDay)
        val previousDayTotal = previousDayStats.sumOf { it.totalTime }

        val selectedStats = getFilteredStatsForDay(selectedDate)
        val selectedTotal = selectedStats.sumOf { it.totalTime }

        val comparisonLabel = if (isToday) "yesterday" else "the previous day"

        val text = if (previousDayTotal > 0 && selectedTotal > 0) {
            val diff = ((selectedTotal - previousDayTotal).toDouble() / previousDayTotal * 100).toInt()
            if (diff < 0) {
                "\"You spent ${-diff}% less time on your device\ncompared to $comparisonLabel.\""
            } else if (diff > 0) {
                "\"You spent ${diff}% more time on your device\ncompared to $comparisonLabel.\""
            } else {
                "\"You spent the same amount of time on your\ndevice as $comparisonLabel.\""
            }
        } else if (previousDayTotal == 0L && selectedTotal > 0) {
            "\"No usage data available for $comparisonLabel.\""
        } else if (selectedTotal == 0L) {
            "\"No usage data recorded for this day.\""
        } else {
            ""
        }

        withContext(Dispatchers.Main) {
            _comparisonText.value = text
            _isLoading.value = false
        }
    }

    private fun getWeekStart(offset: Int): LocalDate {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
    }

    private fun getStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        val today = LocalDate.now()
        val shouldBypassLongCache = !date.isBefore(today.minusDays(1))

        if (shouldBypassLongCache) {
            return usageStatsHelper.getForegroundStatsByDay(date).also {
                dayStatsCache[date] = it
            }
        }

        return dayStatsCache.computeIfAbsent(date) {
            usageStatsHelper.getForegroundStatsByDay(it)
        }
    }

    private fun getFilteredStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return getStatsForDay(date).filter {
            it.totalTime >= 180_000 && it.packageName !in ignoredPackages
        }
    }

    private suspend fun preloadAppMetadata(packageNames: Collection<String>) {
        withContext(Dispatchers.IO) {
            packageNames.distinct().forEach { packageName ->
                getAppMetadata(packageName)
            }
        }
    }

    private fun rememberSnapshotMetadata(stats: Collection<AllAppsUsageFragment.Stat>) {
        stats.forEach { stat ->
            if (stat.snapshotLabel.isNullOrBlank() && stat.snapshotCategory.isNullOrBlank()) {
                return@forEach
            }

            snapshotMetadataCache[stat.packageName] = AppMetadata(
                label = stat.snapshotLabel ?: stat.packageName,
                category = stat.snapshotCategory ?: "APP",
                isSystemApp = false,
                installDate = "N/A",
                lastUpdate = "N/A",
                icon = null
            )
            appMetadataCache.remove(stat.packageName)
        }
    }

    private fun invalidateDynamicDayCache() {
        val today = LocalDate.now()
        dayStatsCache.remove(today)
        dayStatsCache.remove(today.minusDays(1))
    }

    fun getAppMetadata(packageName: String): AppMetadata {
        appMetadataCache[packageName]?.let { return it }

        val metadata = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val category = when (appInfo.category) {
                ApplicationInfo.CATEGORY_GAME -> "GAME"
                ApplicationInfo.CATEGORY_SOCIAL -> "SOCIAL NETWORKING"
                ApplicationInfo.CATEGORY_PRODUCTIVITY -> "PRODUCTIVITY"
                ApplicationInfo.CATEGORY_VIDEO -> "VIDEO"
                ApplicationInfo.CATEGORY_AUDIO -> "AUDIO"
                ApplicationInfo.CATEGORY_NEWS -> "NEWS"
                ApplicationInfo.CATEGORY_IMAGE -> "IMAGE"
                ApplicationInfo.CATEGORY_MAPS -> "MAPS"
                else -> "APP"
            }

            AppMetadata(
                label = appInfo.loadLabel(packageManager),
                category = category,
                isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                installDate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(packageInfo.firstInstallTime)),
                lastUpdate = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date(packageInfo.lastUpdateTime)),
                icon = appInfo.loadIcon(packageManager)
            )
        } catch (_: Exception) {
            snapshotMetadataCache[packageName] ?: AppMetadata(
                label = packageName,
                category = "APP",
                isSystemApp = false,
                installDate = "N/A",
                lastUpdate = "N/A",
                icon = null
            )
        }

        appMetadataCache[packageName] = metadata
        return metadata
    }

    fun getAppCategory(packageName: String): String {
        return getAppMetadata(packageName).category
    }
}
