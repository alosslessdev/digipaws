package neth.iecal.curbox.data.models

data class KeywordBlocker(
    val isActive: Boolean = false,
    val blockedKeywords: List<String> = emptyList(),
    val redirectUrl: String = "https://curbox.life",
    val searchRecursively: Boolean = false,
    val matchSubstrings: Boolean = false,
    val blockAllExceptSupported: Boolean = false,
    val ignoredApps: List<String> = emptyList(),
    val isTimeTrackingEnabled: Boolean = false,
    val keywordTimeLimits: Map<String, Int> = emptyMap(),
    val keywordReminderIntervals: Map<String, Int> = emptyMap(),
    val clusteringThresholdMinutes: Int = 5,
    val keywordFocusGroups: Map<String, List<String>> = emptyMap()
)

data class KeywordDetection(
    val keyword: String,
    val timestamp: Long,
    val packageName: String
)

data class KeywordDetectionCluster(
    val keyword: String,
    val startTime: Long,
    val endTime: Long,
    val durationSeconds: Long,
    val detectionCount: Int
)
