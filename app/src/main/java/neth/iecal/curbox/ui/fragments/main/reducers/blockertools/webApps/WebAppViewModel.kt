package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.webApps

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.WebApp
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.BridgeServiceManager

class WebAppViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)

    private val _webApps = MutableStateFlow<List<WebApp>>(emptyList())
    val webApps: StateFlow<List<WebApp>> = _webApps

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _webApps.value = settings.webApps
            }
        }
    }

    fun addWebApp(webApp: WebApp) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentWebApps = currentSettings.webApps.toMutableList()
            currentWebApps.add(webApp)
            dataStoreManager.updateWebApps(currentWebApps)
            requestKeywordBlockerRefresh()
        }
    }

    fun removeWebApp(webApp: WebApp) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentWebApps = currentSettings.webApps.toMutableList()
            currentWebApps.removeAll { it.id == webApp.id }
            dataStoreManager.updateWebApps(currentWebApps)
            requestKeywordBlockerRefresh()
        }
    }

    fun updateWebApp(webApp: WebApp) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settings.first()
            val currentWebApps = currentSettings.webApps.toMutableList()
            val index = currentWebApps.indexOfFirst { it.id == webApp.id }
            if (index != -1) {
                currentWebApps[index] = webApp
                dataStoreManager.updateWebApps(currentWebApps)
                requestKeywordBlockerRefresh()
            }
        }
    }

    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
        BridgeServiceManager.sendBridgedBroadcast(getApplication(), intent)
    }
}
