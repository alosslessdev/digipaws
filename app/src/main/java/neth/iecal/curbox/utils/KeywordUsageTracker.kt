package neth.iecal.curbox.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.data.models.KeywordDetection
import neth.iecal.curbox.data.models.KeywordDetectionCluster
import java.io.File

class KeywordUsageTracker(private val context: Context) {

    companion object {
        private const val DETECTIONS_FILE = "keyword_detections.json"
        private const val DEFAULT_CLUSTERING_THRESHOLD_MS = 5 * 60 * 1000L
        private const val TAG = "KeywordUsageTracker"
    }

    private val gson = Gson()
    private val detectionsFile = File(context.filesDir, DETECTIONS_FILE)
    private var cachedDetections: MutableMap<String, MutableList<KeywordDetection>>? = null

    private fun loadDetections(): MutableMap<String, MutableList<KeywordDetection>> {
        cachedDetections?.let { return it }

        return try {
            if (detectionsFile.exists()) {
                val json = detectionsFile.readText()
                val type = object : TypeToken<MutableMap<String, MutableList<KeywordDetection>>>() {}.type
                gson.fromJson(json, type) ?: mutableMapOf<String, MutableList<KeywordDetection>>()
            } else {
                mutableMapOf<String, MutableList<KeywordDetection>>()
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadDetections", e)
            mutableMapOf<String, MutableList<KeywordDetection>>()
        }.also { cachedDetections = it }
    }

    private fun saveDetections(detections: MutableMap<String, MutableList<KeywordDetection>>) {
        try {
            detectionsFile.writeText(gson.toJson(detections))
            cachedDetections = detections
        } catch (e: Exception) {
            Log.e(TAG, "saveDetections", e)
        }
    }

    fun recordDetection(keyword: String, packageName: String) {
        val detections = loadDetections()
        val keywordDetections = detections.getOrPut(keyword) { mutableListOf() }
        keywordDetections.add(
            KeywordDetection(
                keyword = keyword,
                timestamp = System.currentTimeMillis(),
                packageName = packageName
            )
        )
        saveDetections(detections)
        Log.d(TAG, "Recorded detection for keyword: $keyword")
    }

    fun getDetectionsForKeyword(keyword: String): List<KeywordDetection> {
        return loadDetections()[keyword] ?: emptyList()
    }

    fun getTodayDetectionsForKeyword(keyword: String): List<KeywordDetection> {
        val todayStart = getTodayStartTimestamp()
        return getDetectionsForKeyword(keyword).filter { it.timestamp >= todayStart }
    }

    fun clusterDetections(
        detections: List<KeywordDetection>,
        clusteringThresholdMs: Long = DEFAULT_CLUSTERING_THRESHOLD_MS
    ): List<KeywordDetectionCluster> {
        if (detections.isEmpty()) return emptyList()

        val sortedDetections = detections.sortedBy { it.timestamp }
        val clusters = mutableListOf<KeywordDetectionCluster>()
        var clusterStart = sortedDetections.first().timestamp
        var clusterEnd = clusterStart
        var detectionCount = 1

        for (i in 1 until sortedDetections.size) {
            val current = sortedDetections[i]
            val gap = current.timestamp - clusterEnd

            if (gap <= clusteringThresholdMs) {
                clusterEnd = current.timestamp
                detectionCount++
            } else {
                clusters.add(
                    KeywordDetectionCluster(
                        keyword = sortedDetections.first().keyword,
                        startTime = clusterStart,
                        endTime = clusterEnd,
                        durationSeconds = (clusterEnd - clusterStart) / 1000,
                        detectionCount = detectionCount
                    )
                )
                clusterStart = current.timestamp
                clusterEnd = current.timestamp
                detectionCount = 1
            }
        }

        clusters.add(
            KeywordDetectionCluster(
                keyword = sortedDetections.first().keyword,
                startTime = clusterStart,
                endTime = clusterEnd,
                durationSeconds = (clusterEnd - clusterStart) / 1000,
                detectionCount = detectionCount
            )
        )

        return clusters
    }

    fun calculateTotalUsageTimeForToday(
        keyword: String,
        clusteringThresholdMs: Long = DEFAULT_CLUSTERING_THRESHOLD_MS
    ): Long {
        val todayDetections = getTodayDetectionsForKeyword(keyword)
        val clusters = clusterDetections(todayDetections, clusteringThresholdMs)
        return clusters.sumOf { it.durationSeconds }
    }

    fun calculateTotalUsageMinutesForToday(
        keyword: String,
        clusteringThresholdMs: Long = DEFAULT_CLUSTERING_THRESHOLD_MS
    ): Double {
        return calculateTotalUsageTimeForToday(keyword, clusteringThresholdMs) / 60.0
    }

    fun checkAndResetIfNewDay() {
        val detections = loadDetections()
        val todayStart = getTodayStartTimestamp()

       val toRemove = detections.filter { (_, list) ->
            list.all { it.timestamp < todayStart }
        }.keys

        if (toRemove.isNotEmpty()) {
            toRemove.forEach { detections.remove(it) }
            saveDetections(detections)
            Log.d(TAG, "Cleared detections for past days")
        }
    }

    fun clearDetectionsForKeyword(keyword: String) {
        val detections = loadDetections()
        detections.remove(keyword)
        saveDetections(detections)
    }

    fun clearAllDetections() {
        saveDetections(mutableMapOf())
    }

    private fun getTodayStartTimestamp(): Long {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }
}
