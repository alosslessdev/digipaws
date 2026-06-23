package neth.iecal.curbox.data.models

data class KeywordBlocker(
    val isActive: Boolean = false,
    val keywordGroups: List<KeywordGroup> = emptyList(),
    val blockAllExceptSupported: Boolean = false,
    val redirectUrl: String = "https://curbox.life",
    val searchRecursively: Boolean = false,
    val matchSubstrings: Boolean = false,
    val ignoredApps: List<String> = emptyList(),
    val isTimeTrackingEnabled: Boolean = false,
    val keywordTimeLimits: Map<String, Int> = emptyMap(),
    val keywordReminderIntervals: Map<String, Int> = emptyMap(),
    val clusteringThresholdMinutes: Int = 5
)

data class KeywordGroup(
    val id: String = "",
    val name: String = "name",
    val selectedKeywords: List<String> = listOf(),
    val blockingType: AppBlockingType = AppBlockingType.Usage,
    val isActive: Boolean = false,
    val setting: String = "",
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig()
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
