package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.utils.DataStoreManager
<<<<<<< HEAD
import neth.iecal.curbox.utils.BridgeServiceManager
import neth.iecal.curbox.utils.KeywordBlockerMatchUtils
import neth.iecal.curbox.utils.KeywordUsageTracker
=======
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26

class KeywordBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    private val usageTracker = KeywordUsageTracker(application)

    private val _keywordBlockerConfig = MutableStateFlow(KeywordBlocker())
    val keywordBlockerConfig: StateFlow<KeywordBlocker> = _keywordBlockerConfig

    var currentUsageConfig = AppUsageConfig()
    var currentTimeConfig = AppTimeConfig()
    var warningScrnConfig = AppBlockerWarningScreenConfig()

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _keywordBlockerConfig.value = settings.keywordBlockerConfig
            }
        }
    }


    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
<<<<<<< HEAD
        BridgeServiceManager.sendBridgedBroadcast(application, intent)
=======
        getApplication<Application>().sendBroadcast(intent)
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
    }
    private fun updateConfig(transform: (neth.iecal.curbox.data.models.KeywordBlocker) -> neth.iecal.curbox.data.models.KeywordBlocker) {
        viewModelScope.launch {
            dataStoreManager.updateKeywordBlockerConfig(transform)
            requestKeywordBlockerRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
<<<<<<< HEAD
        updateConfig(_keywordBlockerConfig.value.copy(isActive = isActive))
    }

    fun addKeyword(keyword: String) {
        val currentKeywords = _keywordBlockerConfig.value.blockedKeywords.toMutableList()
        val normalizedKeyword = KeywordBlockerMatchUtils.normalizeBlockedEntry(keyword)
        val existingKeywords = currentKeywords.map(KeywordBlockerMatchUtils::normalizeBlockedEntry)
        if (!existingKeywords.contains(normalizedKeyword) && normalizedKeyword.isNotBlank()) {
            currentKeywords.add(normalizedKeyword)
            updateConfig(_keywordBlockerConfig.value.copy(blockedKeywords = currentKeywords))
        }
    }

    fun removeKeyword(keyword: String) {
        val currentKeywords = _keywordBlockerConfig.value.blockedKeywords.toMutableList()
        val normalizedKeyword = KeywordBlockerMatchUtils.normalizeBlockedEntry(keyword)
        val removed = currentKeywords.removeAll {
            KeywordBlockerMatchUtils.normalizeBlockedEntry(it) == normalizedKeyword
        }
        if (removed) {
            updateConfig(_keywordBlockerConfig.value.copy(blockedKeywords = currentKeywords))
        }
    }

    fun setIgnoredApps(list:List<String>){
        updateConfig(_keywordBlockerConfig.value.copy(ignoredApps = list))
    }
    fun setRedirectUrl(url: String) {
        updateConfig(_keywordBlockerConfig.value.copy(redirectUrl = url))
    }

    fun setSearchRecursively(enabled: Boolean) {
        updateConfig(_keywordBlockerConfig.value.copy(searchRecursively = enabled))
=======
        updateConfig { it.copy(isActive = isActive) }
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
    }

    fun setMatchSubstrings(enabled: Boolean) {
        updateConfig(_keywordBlockerConfig.value.copy(matchSubstrings = enabled))
    }

    fun setBlockAllExceptSupported(enabled: Boolean) {
        updateConfig { it.copy(blockAllExceptSupported = enabled) }
    }

    fun addGroup(group: KeywordGroup) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.add(group)
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupById(group: KeywordGroup) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                groups[index] = group
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun deleteGroup(groupId: String) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            groups.removeAll { it.id == groupId }
            config.copy(keywordGroups = groups)
        }
    }

    fun updateGroupActiveState(groupId: String, isActive: Boolean) {
        updateConfig { config ->
            val groups = config.keywordGroups.toMutableList()
            val index = groups.indexOfFirst { it.id == groupId }
            if (index != -1) {
                groups[index] = groups[index].copy(isActive = isActive)
            }
            config.copy(keywordGroups = groups)
        }
    }

    fun setTimeTrackingEnabled(enabled: Boolean) {
        updateConfig(_keywordBlockerConfig.value.copy(isTimeTrackingEnabled = enabled))
    }

    fun setClusteringThreshold(minutes: Int) {
        updateConfig(_keywordBlockerConfig.value.copy(clusteringThresholdMinutes = minutes))
    }

    fun setKeywordTimeLimit(keyword: String, minutes: Int) {
        val currentLimits = _keywordBlockerConfig.value.keywordTimeLimits.toMutableMap()
        if (minutes > 0) {
            currentLimits[keyword] = minutes
        } else {
            currentLimits.remove(keyword)
        }
        updateConfig(_keywordBlockerConfig.value.copy(keywordTimeLimits = currentLimits))
    }

    fun setKeywordReminderInterval(keyword: String, minutes: Int) {
        val currentIntervals = _keywordBlockerConfig.value.keywordReminderIntervals.toMutableMap()
        if (minutes > 0) {
            currentIntervals[keyword] = minutes
        } else {
            currentIntervals.remove(keyword)
        }
        updateConfig(_keywordBlockerConfig.value.copy(keywordReminderIntervals = currentIntervals))
    }

    fun getKeywordUsageMinutes(keyword: String): Double {
        val clusteringThresholdMs = _keywordBlockerConfig.value.clusteringThresholdMinutes * 60 * 1000L
        return usageTracker.calculateTotalUsageMinutesForToday(keyword, clusteringThresholdMs)
    }

    fun clearKeywordUsage(keyword: String) {
        usageTracker.clearDetectionsForKeyword(keyword)
    }

    fun clearAllUsage() {
        usageTracker.clearAllDetections()
    }
}
