package neth.iecal.curbox.ui.fragments.usage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.utils.DataStoreManager
import android.content.Intent
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.utils.BridgeServiceManager

class SetupShortcutViewModel(application: Application) : AndroidViewModel(application) {
    private var dataStoreManager = DataStoreManager(application)
    
    private val _settings = MutableStateFlow<Settings?>(null)
    val settings: StateFlow<Settings?> = _settings
    
    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest {
                _settings.value = it
            }
        }
    }
    
    fun toggleAppGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedGroups = currentSettings.blockedAppGroups.map {
                if (it.id == groupId) it.copy(isActive = isActive) else it
            }
            dataStoreManager.updateAppGroups(updatedGroups)
            BridgeServiceManager.sendBridgedBroadcast(getApplication(), Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER))
        }
    }

    fun toggleGrayscaleGroup(groupId: String, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = _settings.value ?: return@launch
            val updatedGroups = currentSettings.grayscaleGroups.map {
                if (it.groupId == groupId) it.copy(isActive = isActive) else it
            }
            dataStoreManager.updateGrayscaleGroups(updatedGroups)
            BridgeServiceManager.sendBridgedBroadcast(getApplication(), Intent("neth.iecal.curbox.ACTION_REFRESH_GRAYSCALE"))
        }
    }
}
