package neth.iecal.curbox.utils

import android.content.Context
<<<<<<< HEAD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.ui.fragments.usage.AllAppsUsageFragment
=======
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageStatsHelper(context: Context) {

<<<<<<< HEAD
    private val usageStatsManager =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    private val dataStoreManager = DataStoreManager(context)
    private val snapshotStore = UsageStatsSnapshotStore(context)
    private val zoneId = ZoneId.systemDefault()
=======
    private val dao = AppDatabase.getInstance(context.applicationContext).appUsageDao()
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26

    suspend fun getForegroundStatsByRelativeDay(offset: Int): List<AllAppsUsageFragment.Stat> {
        return getForegroundStatsByDay(LocalDate.now().minusDays(offset.toLong()))
    }

    suspend fun getForegroundStatsByDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return dao.getForDate(TimeTools.dayKey(date))
            .map { it.toStat() }
            .sortedByDescending { it.totalTime }
    }

<<<<<<< HEAD
    private data class RangeKey(
        val start: Long,
        val end: Long,
        val keepUninstalledUsageUntilNextDay: Boolean
    )

    private val rangeCache = ConcurrentHashMap<RangeKey, CachedRange>()

    fun getForegroundStatsByTimestamps(start: Long, end: Long): List<AllAppsUsageFragment.Stat> {
        val keepUninstalledUsageUntilNextDay = isKeepUninstalledUsageUntilNextDayEnabled()
        val rangeKey = RangeKey(start, end, keepUninstalledUsageUntilNextDay)
        val now = System.currentTimeMillis()
        rangeCache[rangeKey]?.let { cached ->
            if (isCacheValid(rangeKey, cached.cachedAt, now)) {
                return cached.stats
            }
        }

        // List to store currently running foreground processes
        val foregroundProcesses = mutableListOf<String>()
        if (end >= System.currentTimeMillis() - 1500) {
            // Get currently running foreground processes
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val appProcesses = activityManager.runningAppProcesses
            for (appProcess in appProcesses) {
                if (appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    appProcess.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    foregroundProcesses.add(appProcess.processName)
                }
            }
        }

        // Query usage events from the UsageStatsManager
        val events = usageStatsManager.queryEvents(start, end)
        // Map to track when apps move to the foreground (nullable Long to handle null values)
        val moveToForegroundMap = mutableMapOf<AppClass, Long?>()
        // List to store foreground stats for each app
        val componentForegroundStats = mutableListOf<ComponentForegroundStat>()

        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)

            // Skip if className is null
            val className = event.className ?: continue

            val appClass = AppClass(event.packageName, className)

            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED, 4 -> {
                    // App moved to the foreground: store the timestamp
                    moveToForegroundMap[appClass] = event.timeStamp
                }
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED, 3 -> {
                    // App moved to the background: calculate usage duration
                    var eventBeginTime: Long? = moveToForegroundMap[appClass]
                    if (eventBeginTime != null) {
                        // If there's a start time, set it to null (app is no longer in the foreground)
                        moveToForegroundMap[appClass] = null
                    } else if (moveToForegroundMap.keys.none { event.packageName == it.packageName } &&
                        guardian.test(event, start)) {
                        // If no start time exists and the guardian confirms it's a valid unmatched close event, use the start time
                        eventBeginTime = start
                    } else {
                        // Skip if it's a faulty unmatched close event
                        continue
                    }

                    // Calculate the end time, handling null values
                    val endTime = moveToForegroundMap.entries
                        .filter { event.packageName == it.key.packageName }
                        .filter { it.value != null }
                        .map { it.value!! } // Use non-null assertion here since we filtered out nulls
                        .minOrNull() ?: event.timeStamp

                    // Add the foreground stat
                    componentForegroundStats.add(ComponentForegroundStat(eventBeginTime, endTime, event.packageName))
                }
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    // Handle device shutdown: treat all open events as closed
                    for (key in moveToForegroundMap.keys) {
                        val startTime = moveToForegroundMap[key]
                        if (startTime == null) continue // Skip if no start time exists

                        // Add the foreground stat for the shutdown event
                        componentForegroundStats.add(ComponentForegroundStat(startTime, event.timeStamp, key.packageName))

                        // Set all components of the app to null (no longer in the foreground)
                        moveToForegroundMap.keys.filter { key.packageName == it.packageName }.forEach { moveToForegroundMap[it] = null }
                    }
                }
                UsageEvents.Event.DEVICE_STARTUP -> {
                    // Handle device startup: clear all open events
                    for (key in moveToForegroundMap.keys) {
                        moveToForegroundMap[key] = null
                    }
                    // Update the start time to the startup event's timestamp
                    start.coerceAtLeast(event.timeStamp)
                }
            }
        }

        // Handle remaining open events (apps still in the foreground)
        for (key in moveToForegroundMap.keys) {
            val startTime = moveToForegroundMap[key]
            if (startTime == null) continue // Skip if no start time exists

            // Check if the app is still in the foreground
            for (foregroundProcess in foregroundProcesses) {
                if (foregroundProcess.contains(key.packageName)) {
                    // Add the foreground stat for the remaining open event
                    componentForegroundStats.add(ComponentForegroundStat(startTime, minOf(System.currentTimeMillis(), end), key.packageName))
                    break
                }
            }
        }

        // If no events were found but there are foreground processes, assume they were used the entire period
        if (moveToForegroundMap.isEmpty()) {
            val packageManager = context.packageManager
            for (foregroundProcess in foregroundProcesses) {
                if (packageManager.getLaunchIntentForPackage(foregroundProcess) != null) {
                    AppLogger.logDebug("UsageStatsHelper", "Assuming that application $foregroundProcess has been used the whole query time")
                    componentForegroundStats.add(ComponentForegroundStat(start, minOf(System.currentTimeMillis(), end), foregroundProcess))
                }
            }
        }

        // Aggregate the foreground stats into usage stats
        val liveStats = aggregateForegroundStats(componentForegroundStats)
        val queryDate = getSingleDay(start, end)
        val mergedStats = queryDate?.let { snapshotStore.mergeAndPersist(it, liveStats) }
        val statsToReturn = if (queryDate != null && keepUninstalledUsageUntilNextDay) {
            mergedStats ?: liveStats
        } else {
            liveStats
        }

        return statsToReturn.also { stats ->
            rangeCache[rangeKey] = CachedRange(stats, now)
            pruneCache()
        }
    }
    fun getForegroundStatsByRelativeDay(offset: Int): List<AllAppsUsageFragment.Stat> {
        val queryDay = LocalDate.now().minusDays(offset.toLong())
        val start = queryDay.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val end = queryDay.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return getForegroundStatsByTimestamps(start, end)
=======
    suspend fun getForegroundStatsByTimestamps(start: Long, end: Long): List<AllAppsUsageFragment.Stat> {
        val dates = datesBetween(start, end)
        if (dates.isEmpty()) return emptyList()

        val merged = HashMap<String, AllAppsUsageFragment.Stat>()
        for (row in dao.getForDates(dates)) {
            val existing = merged[row.packageName]
            if (existing == null) {
                merged[row.packageName] = row.toStat()
            } else {
                val hourly = existing.hourlyUsage.copyOf()
                val incoming = parseHourly(row.hourlyUsage)
                for (i in 0 until 24) hourly[i] += incoming[i]
                merged[row.packageName] = AllAppsUsageFragment.Stat(
                    packageName = row.packageName,
                    totalTime = existing.totalTime + row.totalTime,
                    sessions = existing.sessions + row.launchCount,
                    hourlyUsage = hourly
                )
            }
        }
        return merged.values.sortedByDescending { it.totalTime }
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
    }

    suspend fun getEarliestTimestamp(): Long = dao.earliestTimestamp() ?: System.currentTimeMillis()

    private fun datesBetween(start: Long, end: Long): List<String> {
        val zone = ZoneId.systemDefault()
        val startDate = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
        val endDate = Instant.ofEpochMilli(end).atZone(zone).toLocalDate()
        if (endDate.isBefore(startDate)) return emptyList()

        val result = ArrayList<String>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            result.add(TimeTools.dayKey(cursor))
            cursor = cursor.plusDays(1)
        }
        return result
    }

    private fun AppUsageEntity.toStat(): AllAppsUsageFragment.Stat {
        return AllAppsUsageFragment.Stat(
            packageName = packageName,
            totalTime = totalTime,
            sessions = launchCount,
            hourlyUsage = parseHourly(hourlyUsage)
        )
    }

    private fun parseHourly(serialized: String?): LongArray {
        val result = LongArray(24)
        if (serialized.isNullOrEmpty()) return result
        val parts = serialized.split(',')
        for (i in 0 until minOf(24, parts.size)) {
            result[i] = parts[i].toLongOrNull() ?: 0L
        }
        return result
    }
<<<<<<< HEAD

    private fun getSingleDay(start: Long, end: Long): LocalDate? {
        if (end <= start) return null

        val startDate = Instant.ofEpochMilli(start).atZone(zoneId).toLocalDate()
        val endDate = Instant.ofEpochMilli(end - 1).atZone(zoneId).toLocalDate()
        return startDate.takeIf { it == endDate }
    }

    private fun isKeepUninstalledUsageUntilNextDayEnabled(): Boolean {
        return runBlocking {
            dataStoreManager.settings.first().keepUninstalledUsageUntilNextDay
        }
    }

    private fun aggregateForegroundStats(foregroundStats: List<ComponentForegroundStat>): List<AllAppsUsageFragment.Stat> {
        val usageStats = mutableListOf<AllAppsUsageFragment.Stat>()
        if (foregroundStats.isEmpty()) return usageStats

        // Map to store total foreground time for each app
        val applicationTotalForegroundTime = mutableMapOf<String, Long>()
        // Map to store start times for each app
        val applicationStartTimes = mutableMapOf<String, MutableList<ZonedDateTime>>()
        // Map to store hourly usage for each app (in milliseconds)
        val applicationHourlyUsage = mutableMapOf<String, LongArray>()

        for (foregroundStat in foregroundStats) {
            val packageName = foregroundStat.packageName
            val beginTime = foregroundStat.beginTime
            val endTime = foregroundStat.endTime

            // Calculate total foreground time for each app
            applicationTotalForegroundTime[packageName] =
                applicationTotalForegroundTime.getOrDefault(packageName, 0) +
                        (endTime - beginTime)

            // Collect start times for each app
            val startTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(beginTime),
                ZoneId.systemDefault()
            )
            applicationStartTimes.getOrPut(packageName) { mutableListOf() }.add(startTime)

            // Aggregate hourly usage
            val hourlyUsage = applicationHourlyUsage.getOrPut(packageName) { LongArray(24) }
            var currentStart = beginTime
            while (currentStart < endTime) {
                val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(currentStart), ZoneId.systemDefault())
                val currentHour = zdt.hour
                
                // Calculate when the next hour starts
                val nextHourStart = zdt.plusHours(1).withMinute(0).withSecond(0).withNano(0).toInstant().toEpochMilli()
                
                // End of this chunk is either the actual end time, or the start of the next hour
                val blockEnd = minOf(endTime, nextHourStart)
                hourlyUsage[currentHour] += (blockEnd - currentStart)
                
                currentStart = blockEnd
            }
        }

        // Create Stat objects with total time and start times
        for ((packageName, totalTime) in applicationTotalForegroundTime) {
            val startTimes = applicationStartTimes[packageName] ?: listOf()
            val hourlyUsage = applicationHourlyUsage[packageName] ?: LongArray(24)
            usageStats.add(AllAppsUsageFragment.Stat(packageName, totalTime, startTimes, hourlyUsage))
        }

        // Sort by total time in descending order
        return usageStats.sortedByDescending { it.totalTime }
    }

    private class AppClass(val packageName: String, val className: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AppClass
            if (packageName != other.packageName) return false
            if (className != other.className) return false
            return true
        }

        override fun hashCode(): Int {
            var result = packageName.hashCode()
            result = 31 * result + className.hashCode()
            return result
        }
    }

    private class ComponentForegroundStat(val beginTime: Long, val endTime: Long, val packageName: String)

    private class UnmatchedCloseEventGuardian {
        fun test(event: UsageEvents.Event, start: Long): Boolean {
            // Implement logic to test for unmatched close events
            return true
        }
    }


=======
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
}
