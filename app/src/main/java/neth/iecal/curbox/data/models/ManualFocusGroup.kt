package neth.iecal.curbox.data.models

import java.util.UUID

data class ManualFocusGroup(
    override val groupId: String = UUID.randomUUID().toString(),
    override val groupName: String,
    val packages: HashSet<String>,
    val blockMode: FocusBlockMode,
    val exitable: Boolean = true,
    val autoTurnOnDnd: Boolean = false,
    val isRecurring: Boolean = false,
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
): FocusGroup {
    override fun toString(): String {
        return "$groupName (${packages.size} ${
            if(blockMode == FocusBlockMode.BLOCK_SELECTED) "included" else "excluded"
        } apps)"
    }

}
