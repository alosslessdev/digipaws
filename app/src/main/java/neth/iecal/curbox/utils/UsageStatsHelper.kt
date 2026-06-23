package neth.iecal.curbox.utils

import android.content.Context
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.AppUsageEntity
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class UsageStatsHelper(context: Context) {

    private val dao = AppDatabase.getInstance(context.applicationContext).appUsageDao()

    suspend fun getForegroundStatsByRelativeDay(offset: Int): List<AllAppsUsageFragment.Stat> {
        return getForegroundStatsByDay(LocalDate.now().minusDays(offset.toLong()))
    }

    suspend fun getForegroundStatsByDay(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        return dao.getForDate(TimeTools.dayKey(date))
            .map { it.toStat() }
            .sortedByDescending { it.totalTime }
    }

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
}
