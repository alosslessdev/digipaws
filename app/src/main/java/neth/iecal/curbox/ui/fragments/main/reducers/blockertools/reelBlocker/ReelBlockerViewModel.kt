package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.ReelBlocker
import neth.iecal.curbox.data.models.ReelCountConfig
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.data.models.ReelUsageConfig
import neth.iecal.curbox.data.models.ReelBlockingType
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.BridgeServiceManager

class ReelBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    private val gson = Gson()

    private val _reelBlockerConfig = MutableStateFlow(ReelBlocker())
    val reelBlockerConfig: StateFlow<ReelBlocker> = _reelBlockerConfig

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _reelBlockerConfig.value = settings.reelBlockerConfig
            }
        }
    }


    private fun requestReelBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.ReelBlocker.INTENT_ACTION_REFRESH_REEL_BLOCKER)
        BridgeServiceManager.sendBridgedBroadcast(application, intent)
    }
    private fun updateConfig(newConfig: ReelBlocker) {
        viewModelScope.launch {
            dataStoreManager.updateReelBlockerConfig(newConfig)
            requestReelBlockerRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
        updateConfig(_reelBlockerConfig.value.copy(isActive = isActive))
    }

    fun setBlockingType(type: ReelBlockingType) {
        updateConfig(_reelBlockerConfig.value.copy(blockingType = type))
    }

    fun updateWarningConfig(config: AppBlockerWarningScreenConfig) {
        updateConfig(_reelBlockerConfig.value.copy(warningScreenConfig = config))
    }

    fun getReelTimeConfig(): ReelTimeConfig {
        return try {
            if (_reelBlockerConfig.value.settings.isEmpty()) ReelTimeConfig()
            else gson.fromJson(_reelBlockerConfig.value.settings, ReelTimeConfig::class.java)
        } catch(e: Exception) { ReelTimeConfig() }
    }

    fun saveReelTimeConfig(config: ReelTimeConfig) {
        updateConfig(_reelBlockerConfig.value.copy(settings = gson.toJson(config)))
    }

    fun getReelUsageConfig(): ReelUsageConfig {
        return try {
            if (_reelBlockerConfig.value.settings.isEmpty()) ReelUsageConfig()
            else gson.fromJson(_reelBlockerConfig.value.settings, ReelUsageConfig::class.java)
        } catch(e: Exception) { ReelUsageConfig() }
    }

    fun saveReelUsageConfig(config: ReelUsageConfig) {
        updateConfig(_reelBlockerConfig.value.copy(settings = gson.toJson(config)))
    }

    fun getReelCountConfig(): ReelCountConfig {
        return try {
            if (_reelBlockerConfig.value.settings.isEmpty()) ReelCountConfig()
            else gson.fromJson(_reelBlockerConfig.value.settings, ReelCountConfig::class.java)
        } catch(e: Exception) { ReelCountConfig() }
    }

    fun saveReelCountConfig(config: ReelCountConfig) {
        updateConfig(_reelBlockerConfig.value.copy(settings = gson.toJson(config)))
    }
}
