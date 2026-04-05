package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autofocus

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.FocusModeBlocker
import neth.iecal.curbox.data.models.AutoFocusGroup
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.BridgeServiceManager

class AutoFocusViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    
    private val _groups = MutableStateFlow<List<AutoFocusGroup>>(emptyList())
    val groups: StateFlow<List<AutoFocusGroup>> = _groups

    var currentDailyIntervals: MutableMap<Int, MutableList<neth.iecal.curbox.data.models.TimeInterval>> = mutableMapOf()

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _groups.value = settings.autoFocusGroups
            }
        }
    }

    fun addGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            currentGroups.add(group)
            updateGroups(currentGroups)
        }
    }

    fun removeGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            // We use removeIf to ensure it matches by id in case of object reference mismatch
            currentGroups.removeIf { it.groupId == group.groupId }
            updateGroups(currentGroups)
        }
    }

    fun updateGroup(group: AutoFocusGroup) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentGroups = currentSettings.autoFocusGroups.toMutableList()
            val index = currentGroups.indexOfFirst { it.groupId == group.groupId }
            if (index != -1) {
                currentGroups[index] = group
                updateGroups(currentGroups)
            }
        }
    }

    private fun updateGroups(newGroups: List<AutoFocusGroup>) {
        viewModelScope.launch {
            dataStoreManager.updateAutoFocusGroups(newGroups)
            requestFocusBlockerRefresh()
        }
    }

    private fun requestFocusBlockerRefresh() {
        val intent = Intent(FocusModeBlocker.INTENT_ACTION_REFRESH_FOCUS_MODE)
        BridgeServiceManager.sendBridgedBroadcast(application, intent)
    }
}
