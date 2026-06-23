package neth.iecal.curbox.ui.fragments.main.usage

import android.app.Application
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.utils.DataStoreManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AllAppsUsageViewModel(application: Application) : AndroidViewModel(application) {

    private val usageStatsHelper = UsageStatsHelper(application)
    private val packageManager = application.packageManager
    private val websiteStatsDao = AppDatabase.getInstance(application).websiteStatsDao()

    private val domainRegex = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$", RegexOption.IGNORE_CASE)

    private fun WebsiteStatsEntity.isWebsite(): Boolean =
        domain.isNotBlank() && !domain.contains(' ') && domainRegex.matches(domain)

    val ignoredPackages: MutableSet<String> = mutableSetOf()

    private val dayStatsCache = ConcurrentHashMap<LocalDate, List<AllAppsUsageFragment.Stat>>()
    private val appMetadataCache = ConcurrentHashMap<String, AppMetadata>()

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

    private val _weekOffset = MutableLiveData(0)
    val weekOffset: LiveData<Int> = _weekOffset

    private val _weekRangeLabel = MutableLiveData<String>()
    val weekRangeLabel: LiveData<String> = _weekRangeLabel

    private val _weeklyData = MutableLiveData<List<WeeklyBarGraphView.DayData>>()
    val weeklyData: LiveData<List<WeeklyBarGraphView.DayData>> = _weeklyData

    private val _selectedDayIndex = MutableLiveData(6) 
    val selectedDayIndex: LiveData<Int> = _selectedDayIndex

    private val _selectedDayStats = MutableLiveData<List<AllAppsUsageFragment.Stat>>()
    val selectedDayStats: LiveData<List<AllAppsUsageFragment.Stat>> = _selectedDayStats

    private val _selectedDayWebsiteStats = MutableLiveData<List<WebsiteStatsEntity>>()
    val selectedDayWebsiteStats: LiveData<List<WebsiteStatsEntity>> = _selectedDayWebsiteStats

    private val _totalTime = MutableLiveData<Long>(0L)
    val totalTime: LiveData<Long> = _totalTime

    private val _dateSublabel = MutableLiveData("TOTAL TODAY")
    val dateSublabel: LiveData<String> = _dateSublabel

    private val _canGoNext = MutableLiveData(false)
    val canGoNext: LiveData<Boolean> = _canGoNext

    private val dayLabelFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun initialize() {
        viewModelScope.launch(Dispatchers.IO) {
            getDefaultLauncherPackageName(getApplication<Application>().packageManager)?.let {
                ignoredPackages.add(it)
            }
            val datastore = DataStoreManager(getApplication())
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

        val defaultSelected = if (isCurrentWeek && todayIndex >= 0) todayIndex else 6

        withContext(Dispatchers.Main) {
            _weeklyData.value = dayDataList
            _selectedDayIndex.value = defaultSelected
        }

        val selectedDate = weekStart.plusDays(defaultSelected.toLong())
        loadDayStats(selectedDate)

        withContext(Dispatchers.Main) { _isLoading.value = false }
    }

    private suspend fun loadDayStats(date: LocalDate) {
        val stats = getFilteredStatsForDay(date)
        preloadAppMetadata(stats.map { it.packageName })
        val total = stats.sumOf { it.totalTime }
        val today = LocalDate.now()
        val isToday = date == today

        val sublabel = if (isToday) {
            "TOTAL TODAY"
        } else {
            "TOTAL · ${date.format(dayLabelFormatter)}"
        }

        val dateString = date.format(DateTimeFormatter.ofPattern("dd MMMM yyyy", Locale.getDefault()))
        val websiteStats = websiteStatsDao.getStatsForDate(dateString).filter { it.isWebsite() }

        withContext(Dispatchers.Main) {
            _selectedDayStats.value = stats
            _selectedDayWebsiteStats.value = websiteStats
            _totalTime.value = total
            _dateSublabel.value = sublabel
        }
    }

    private fun getWeekStart(offset: Int): LocalDate {
        return LocalDate.now()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(offset.toLong())
    }

    private suspend fun getStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        val today = LocalDate.now()
        val shouldBypassLongCache = !date.isBefore(today.minusDays(1))

        if (shouldBypassLongCache) {
            return usageStatsHelper.getForegroundStatsByDay(date).also {
                dayStatsCache[date] = it
            }
        }

        return dayStatsCache.computeIfAbsent(date) {
            runBlocking { usageStatsHelper.getForegroundStatsByDay(it) }
        }
    }

    private suspend fun getFilteredStatsForDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return getStatsForDay(date).filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }
    }

    private suspend fun preloadAppMetadata(packageNames: Collection<String>) {
        withContext(Dispatchers.IO) {
            packageNames.distinct().forEach { packageName ->
                getAppMetadata(packageName)
            }
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
                installDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(packageInfo.firstInstallTime)),
                lastUpdate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    .format(Date(packageInfo.lastUpdateTime)),
                icon = appInfo.loadIcon(packageManager)
            )
        } catch (e: Exception) {
            AppMetadata(
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
