package neth.iecal.curbox.data.models


data class AppGroup(
    val id: String = "",
    val name: String = "name",
    val selectedPackages: List<String> = listOf(),
    val blockingType: AppBlockingType = AppBlockingType.Usage, // "USAGE" or "TIME"
    val isActive: Boolean = false,
    val setting:String = "",
    val warningScreenConfig : AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig()
)

enum class AppBlockingType{
    Usage, Timed, OnOpen
}

data class AppTimeConfig(
    var isEveryday: Boolean = true,
    var everydayIntervals: MutableList<TimeInterval> = mutableListOf(TimeInterval()),
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
)


data class AppUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 60,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppUsageConfig

        if (isDailyUniform != other.isDailyUniform) return false
        if (uniformLimit != other.uniformLimit) return false
        if (!dailyLimits.contentEquals(other.dailyLimits)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isDailyUniform.hashCode()
        result = 31 * result + uniformLimit.hashCode()
        result = 31 * result + dailyLimits.contentHashCode()
        return result
    }
}