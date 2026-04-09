package neth.iecal.curbox.data.models

data class KeywordBlocker(
    val isActive: Boolean = false,
    val blockedKeywords: List<String> = emptyList(),
    val redirectUrl: String = "https://curbox.life",
    val searchRecursively: Boolean = false,
    val matchSubstrings: Boolean = false,
    val blockAllExceptSupported: Boolean = false,
    val ignoredApps: List<String> = emptyList()
)
