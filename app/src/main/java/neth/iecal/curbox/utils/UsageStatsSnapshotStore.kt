package neth.iecal.curbox.utils

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.ui.fragments.usage.AllAppsUsageFragment
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class UsageStatsSnapshotStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "usage_stats_snapshots"
        private const val KEY_PREFIX = "day_"
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val snapshotListType = object : TypeToken<List<UsageSnapshot>>() {}.type
    private val zoneId = ZoneId.systemDefault()

    fun mergeAndPersist(
        date: LocalDate,
        liveStats: List<AllAppsUsageFragment.Stat>
    ): List<AllAppsUsageFragment.Stat> {
        pruneOldSnapshots()
        val mergedStats = mergeStats(load(date), liveStats)
        save(date, mergedStats)
        return mergedStats
    }

    private fun load(date: LocalDate): List<AllAppsUsageFragment.Stat> {
        val rawJson = prefs.getString(buildKey(date), null) ?: return emptyList()
        val snapshots = runCatching {
            gson.fromJson<List<UsageSnapshot>>(rawJson, snapshotListType)
        }.getOrNull().orEmpty()

        return snapshots.map { snapshot ->
            AllAppsUsageFragment.Stat(
                packageName = snapshot.packageName,
                totalTime = snapshot.totalTime,
                startTimes = snapshot.startTimesEpochMillis.map {
                    ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zoneId)
                },
                hourlyUsage = snapshot.hourlyUsage.toLongArray(),
                snapshotLabel = snapshot.label,
                snapshotCategory = snapshot.category
            )
        }
    }

    private fun save(date: LocalDate, stats: List<AllAppsUsageFragment.Stat>) {
        val snapshots = stats.map { stat ->
            UsageSnapshot(
                packageName = stat.packageName,
                totalTime = stat.totalTime,
                startTimesEpochMillis = stat.startTimes.map { it.toInstant().toEpochMilli() },
                hourlyUsage = stat.hourlyUsage.toList(),
                label = resolveLabel(stat.packageName, stat.snapshotLabel),
                category = resolveCategory(stat.packageName, stat.snapshotCategory)
            )
        }

        prefs.edit()
            .putString(buildKey(date), gson.toJson(snapshots))
            .apply()
    }

    private fun mergeStats(
        storedStats: List<AllAppsUsageFragment.Stat>,
        liveStats: List<AllAppsUsageFragment.Stat>
    ): List<AllAppsUsageFragment.Stat> {
        val mergedByPackage = linkedMapOf<String, AllAppsUsageFragment.Stat>()

        storedStats.forEach { stat ->
            mergedByPackage[stat.packageName] = stat
        }

        liveStats.forEach { liveStat ->
            val storedStat = mergedByPackage[liveStat.packageName]
            mergedByPackage[liveStat.packageName] = if (storedStat == null) {
                liveStat
            } else {
                mergeStatEntries(storedStat, liveStat)
            }
        }

        return mergedByPackage.values.sortedByDescending { it.totalTime }
    }

    private fun mergeStatEntries(
        storedStat: AllAppsUsageFragment.Stat,
        liveStat: AllAppsUsageFragment.Stat
    ): AllAppsUsageFragment.Stat {
        val mergedHourlyUsage = LongArray(24) { index ->
            maxOf(
                storedStat.hourlyUsage.getOrElse(index) { 0L },
                liveStat.hourlyUsage.getOrElse(index) { 0L }
            )
        }

        val mergedStartTimes = (storedStat.startTimes + liveStat.startTimes)
            .associateBy { it.toInstant().toEpochMilli() }
            .values
            .sortedBy { it.toInstant().toEpochMilli() }

        return AllAppsUsageFragment.Stat(
            packageName = liveStat.packageName,
            totalTime = maxOf(storedStat.totalTime, liveStat.totalTime),
            startTimes = mergedStartTimes,
            hourlyUsage = mergedHourlyUsage,
            snapshotLabel = liveStat.snapshotLabel ?: storedStat.snapshotLabel,
            snapshotCategory = liveStat.snapshotCategory ?: storedStat.snapshotCategory
        )
    }

    private fun resolveLabel(packageName: String, fallback: String?): String {
        return try {
            val applicationInfo = appContext.packageManager.getApplicationInfo(packageName, 0)
            appContext.packageManager.getApplicationLabel(applicationInfo).toString()
        } catch (_: Exception) {
            fallback ?: packageName
        }
    }

    private fun resolveCategory(packageName: String, fallback: String?): String {
        return try {
            when (appContext.packageManager.getApplicationInfo(packageName, 0).category) {
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
        } catch (_: Exception) {
            fallback ?: "APP"
        }
    }

    private fun pruneOldSnapshots() {
        val cutoffDate = LocalDate.now(zoneId).minusDays(1)
        val editor = prefs.edit()
        var hasChanges = false

        prefs.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .forEach { key ->
                val date = runCatching { LocalDate.parse(key.removePrefix(KEY_PREFIX)) }.getOrNull()
                    ?: return@forEach
                if (date.isBefore(cutoffDate)) {
                    editor.remove(key)
                    hasChanges = true
                }
            }

        if (hasChanges) {
            editor.apply()
        }
    }

    private fun buildKey(date: LocalDate): String = "$KEY_PREFIX$date"

    private data class UsageSnapshot(
        val packageName: String,
        val totalTime: Long,
        val startTimesEpochMillis: List<Long>,
        val hourlyUsage: List<Long>,
        val label: String,
        val category: String
    )
}
