package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.BridgeServiceManager
import neth.iecal.curbox.utils.KeywordBlockerMatchUtils
import neth.iecal.curbox.utils.KeywordUsageTracker

class KeywordBlockerViewModel(application: Application) : AndroidViewModel(application) {
    private val dataStoreManager = DataStoreManager(application)
    private val usageTracker = KeywordUsageTracker(application)

    private val _keywordBlockerConfig = MutableStateFlow(KeywordBlocker())
    val keywordBlockerConfig: StateFlow<KeywordBlocker> = _keywordBlockerConfig

    init {
        viewModelScope.launch {
            dataStoreManager.settings.collectLatest { settings ->
                _keywordBlockerConfig.value = settings.keywordBlockerConfig
            }
        }
    }


    private fun requestKeywordBlockerRefresh() {
        val intent = Intent(neth.iecal.curbox.blockers.KeywordBlocker.INTENT_ACTION_REFRESH_CONFIG)
        BridgeServiceManager.sendBridgedBroadcast(application, intent)
    }
    private fun updateConfig(newConfig: KeywordBlocker) {
        viewModelScope.launch {
            dataStoreManager.updateKeywordBlockerConfig(newConfig)
            requestKeywordBlockerRefresh()
        }
    }

    fun setIsActive(isActive: Boolean) {
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
    }

    fun setMatchSubstrings(enabled: Boolean) {
        updateConfig(_keywordBlockerConfig.value.copy(matchSubstrings = enabled))
    }

    fun setBlockAllExceptSupported(enabled: Boolean) {
        updateConfig(_keywordBlockerConfig.value.copy(blockAllExceptSupported = enabled))
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
