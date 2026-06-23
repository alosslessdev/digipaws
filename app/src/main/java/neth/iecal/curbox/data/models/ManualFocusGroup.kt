package neth.iecal.curbox.data.models

import java.util.UUID

data class ManualFocusGroup(
    val groupId: String = UUID.randomUUID().toString(),
    val groupName: String = "",
    val packages: HashSet<String> = hashSetOf(),
    val keywords: HashSet<String> = hashSetOf(),
    val blockMode: FocusBlockMode = FocusBlockMode.BLOCK_SELECTED,
    val exitable: Boolean = true,
    val autoTurnOnDnd: Boolean = false,
    val isRecurring: Boolean = false,
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
){
    override fun toString(): String {
        val mode = if(blockMode == FocusBlockMode.BLOCK_SELECTED) "included" else "excluded"
        return if (keywords.isNotEmpty()) {
            "$groupName (${packages.size} apps, ${keywords.size} websites $mode)"
        } else {
            "$groupName (${packages.size} $mode apps)"
        }
    }

}
