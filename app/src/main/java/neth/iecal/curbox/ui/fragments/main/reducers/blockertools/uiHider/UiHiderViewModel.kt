package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.uihider.UiHider
import neth.iecal.curbox.blockers.uihider.script.Parser
import neth.iecal.curbox.data.models.UiHiderConfig
import neth.iecal.curbox.data.models.UiHiderScript
import neth.iecal.curbox.hardcoded.DEFAULT_UIHIDER_SCRIPTS
import neth.iecal.curbox.utils.DataStoreManager

class UiHiderViewModel(application: Application) : AndroidViewModel(application) {

    private val dataStoreManager = DataStoreManager(application)

    private val _config = MutableStateFlow(UiHiderConfig())
    val config: StateFlow<UiHiderConfig> = _config

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                val persisted = settings.uiHiderConfig
                val merged = mergeWithDefaults(persisted)
                _config.value = merged

                // Persist (and refresh the running blocker) when the shipped defaults changed,
                // e.g. their source was edited in code. Re-emits but converges (merged == persisted next time).
                if (merged.scripts != persisted.scripts) {
                    dataStoreManager.updateUiHiderConfig(merged)
                    requestRefresh()
                }
            }
        }
    }

    /**
     * Shipped sample scripts (ids present in [DEFAULT_UIHIDER_SCRIPTS]) always take their
     * source/label/package from code, keeping only the user's enabled state — so edits to the
     * hardcoded samples propagate automatically. User-created scripts are preserved untouched.
     */
    private fun mergeWithDefaults(persisted: UiHiderConfig): UiHiderConfig {
        val savedById = persisted.scripts.associateBy { it.id }
        val defaultIds = DEFAULT_UIHIDER_SCRIPTS.mapTo(HashSet()) { it.id }

        val mergedDefaults = DEFAULT_UIHIDER_SCRIPTS.map { default ->
            savedById[default.id]?.let { default.copy(isEnabled = it.isEnabled) } ?: default
        }
        val userScripts = persisted.scripts.filter { it.id !in defaultIds }
        return persisted.copy(scripts = mergedDefaults + userScripts)
    }

    private fun updateConfig(newConfig: UiHiderConfig) {
        _config.value = newConfig
        viewModelScope.launch {
            dataStoreManager.updateUiHiderConfig(newConfig)
            requestRefresh()
        }
    }

    private fun requestRefresh() {
        getApplication<Application>().sendBroadcast(
            Intent(UiHider.INTENT_ACTION_REFRESH_UI_HIDER).setPackage(getApplication<Application>().packageName)
        )
    }

    fun setIsActive(isActive: Boolean) {
        updateConfig(_config.value.copy(isActive = isActive))
    }

    fun setScriptEnabled(id: String, enabled: Boolean) {
        val updated = _config.value.scripts.map { if (it.id == id) it.copy(isEnabled = enabled) else it }
        updateConfig(_config.value.copy(scripts = updated))
    }

    /** Insert a new script or replace an existing one with the same id. */
    fun upsertScript(script: UiHiderScript) {
        val current = _config.value.scripts
        val updated = if (current.any { it.id == script.id }) {
            current.map { if (it.id == script.id) script else it }
        } else {
            current + script
        }
        updateConfig(_config.value.copy(scripts = updated))
    }

    fun deleteScript(id: String) {
        updateConfig(_config.value.copy(scripts = _config.value.scripts.filterNot { it.id == id }))
    }

    fun scriptById(id: String): UiHiderScript? = _config.value.scripts.firstOrNull { it.id == id }

    fun newScriptId(): String = "uihider_${System.currentTimeMillis()}"

    /** Compile the source to surface syntax errors; returns the error message, or null if valid. */
    fun validate(source: String): String? = try {
        Parser.parse(source)
        null
    } catch (e: Exception) {
        e.message ?: "Invalid script"
    }
}
