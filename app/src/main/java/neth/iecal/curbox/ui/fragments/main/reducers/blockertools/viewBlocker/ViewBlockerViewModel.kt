package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.viewblocker.ViewBlocker
import neth.iecal.curbox.data.models.ViewBlockerConfig
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.BridgeServiceManager

class ViewBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)

    private val _viewBlockerConfig = MutableStateFlow(ViewBlockerConfig())
    val viewBlockerConfig: StateFlow<ViewBlockerConfig> = _viewBlockerConfig

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                val config = settings.viewBlockerConfig
                if (config.rules.isEmpty()) {
                    val seeded = config.copy(rules = ViewBlocker.DEFAULT_RULES)
                    _viewBlockerConfig.value = seeded
                    dataStoreManager.updateViewBlockerConfig(seeded)
                } else {
                    _viewBlockerConfig.value = config
                }
            }
        }
    }

    private fun requestRefresh() {
        BridgeServiceManager.sendBridgedBroadcast(
            getApplication(),
            Intent(ViewBlocker.INTENT_ACTION_REFRESH_VIEW_BLOCKER)
        )
    }

    private fun updateConfig(newConfig: ViewBlockerConfig) {
        viewModelScope.launch {
            dataStoreManager.updateViewBlockerConfig(newConfig)
            requestRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
        updateConfig(_viewBlockerConfig.value.copy(isActive = isActive))
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        val updatedRules = _viewBlockerConfig.value.rules.map { rule ->
            if (rule.id == ruleId) rule.copy(isEnabled = enabled) else rule
        }
        updateConfig(_viewBlockerConfig.value.copy(rules = updatedRules))
    }

    fun addCustomRule(ruleString: String) {
        if (ruleString.isBlank()) return
        val updated = _viewBlockerConfig.value.customRules.toMutableList()
        if (updated.contains(ruleString)) return
        updated.add(ruleString)
        updateConfig(_viewBlockerConfig.value.copy(customRules = updated))
    }

    fun removeCustomRule(ruleString: String) {
        val updated = _viewBlockerConfig.value.customRules.toMutableList()
        updated.remove(ruleString)
        updateConfig(_viewBlockerConfig.value.copy(customRules = updated))
    }
}
