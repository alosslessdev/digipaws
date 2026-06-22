package neth.iecal.curbox.data.models

import java.util.UUID

data class AutoFocusGroup(
    override val groupId: String = UUID.randomUUID().toString(),
    override val groupName: String,
    val packages: HashSet<String>,
    val blockMode: FocusBlockMode,
    val exitable: Boolean = true,
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf(),
    val autoTurnOnDnd: Boolean = false
): FocusGroup {
    override fun toString(): String {
        return "$groupName (${packages.size} ${
            if (blockMode == FocusBlockMode.BLOCK_SELECTED) "included" else "excluded"
        } apps)"
    }
}