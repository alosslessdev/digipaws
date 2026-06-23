package neth.iecal.curbox.data.models

data class ReelBlocker(
    val warningScreenConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    val blockingType: ReelBlockingType = ReelBlockingType.TIMED,
    val settings: String = "",
    val isActive: Boolean = false
)


enum class ReelBlockingType{
    TIMED, USAGE, REEL_COUNT
}

data class ReelTimeConfig(
    var isEveryday: Boolean = true,
    var everydayIntervals: MutableList<TimeInterval> = mutableListOf(),
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
)


data class ReelUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 0,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReelUsageConfig

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

data class ReelCountConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Int = 10,
    val dailyLimits: IntArray = IntArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ReelCountConfig

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
