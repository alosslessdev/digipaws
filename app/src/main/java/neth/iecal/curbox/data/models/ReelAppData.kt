package neth.iecal.curbox.data.models

import android.view.accessibility.AccessibilityEvent

data class ReelAppData(
    val viewId: String,
    val requiresPresent: List<String>,
    val requiresAbsent: List<String> = emptyList(),
    val dynamicComparator: List<String>,
    val comparsionResultCleanser: (String)->String = {s->s},
    val eventType:Int = AccessibilityEvent.TYPE_VIEW_SCROLLED
)