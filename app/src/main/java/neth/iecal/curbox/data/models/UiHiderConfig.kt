package neth.iecal.curbox.data.models

/**
 * A single user-authored UIHider script, bound to one app package. [source] is the raw script
 * text; it is compiled to an AST at runtime by the UiHider blocker.
 */
data class UiHiderScript(
    val id: String = "",
    val packageName: String = "",
    val label: String = "",
    val source: String = "",
    val isEnabled: Boolean = true
)

/**
 * Top-level configuration for the UIHider feature, stored in DataStore as part of Settings.
 * Scripts only run while [isActive] is true and the script's [UiHiderScript.isEnabled] is set.
 */
data class UiHiderConfig(
    val isActive: Boolean = false,
    val scripts: List<UiHiderScript> = emptyList()
)
