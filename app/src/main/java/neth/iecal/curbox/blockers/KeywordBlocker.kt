package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.Resources
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import androidx.core.content.edit
import androidx.room.InvalidationTracker
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.Constants
import neth.iecal.curbox.R
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.activity.WarningActivity
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.utils.KeywordBlockerMatchUtils
import neth.iecal.curbox.utils.KeywordUsageTracker
import neth.iecal.curbox.utils.TimeTools
import java.util.Calendar
import java.util.Locale

class KeywordBlocker : BaseBlocker() {
    companion object {
        const val INTENT_ACTION_REFRESH_CONFIG = "neth.iecal.curbox.refresh.keywordblocker.config"
        const val INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN = "neth.iecal.curbox.refresh.keywordblocker.cooldown"
        private const val TARGET_EVENTS_MASK =
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

        val URL_BAR_ID_LIST = mapOf(
            "com.android.chrome" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),
            "app.vanadium.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),
            "com.brave.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_bar",
                browserSugggestionBoxId = "omnibox_suggestions_dropdown"
            ),
            "org.mozilla.firefox" to BrowserUrlBarInfo(
                displayUrlBarId = "mozac_browser_toolbar_url_view",
                browserSugggestionBoxId = "sfcnt",
            ),
            "com.opera.browser" to BrowserUrlBarInfo(
                displayUrlBarId = "url_field",
                browserSugggestionBoxId = "right_state_button",
                isSuggestionEqualToGo = true
            ),
        )

        private const val SAFE_STRING_TOKEN = "||SAFE||"
    }

    private lateinit var service: BaseBlockingService
    private lateinit var browserBlocker: BrowserBlocker
    private lateinit var prefs: SharedPreferences

    private var activeGroups = listOf<KeywordGroup>()
    private var groupPatternMap = mutableMapOf<String, Pair<List<Regex>, List<String>>>()

    private val detectionCache = LruCache<String, String>(200)
    private var isTurnedOn = false
    private var isUnsupportedBrowserBlockingOn = false
    private var lastpkg = ""
    private var cooldownGroupsList = HashMap<String, Long>()
    private var observationJob: Job? = null

    private var blockedKeywords: List<String> = emptyList()
    private var redirectUrl: String = ""
    var isSearchAllTextFields = false
    private var isSubstringMatchEnabled = false
    var recursionResultNodes: MutableList<AccessibilityNodeInfo> = mutableListOf()
    private var ignoredApps: HashSet<String> = hashSetOf()
    private var settingsJob: Job? = null

    private var lastEventTimeStamp = 0L
    private var refreshCooldown : Int = 2000

    private var isTimeTrackingEnabled = false
    private var keywordTimeLimits: Map<String, Int> = emptyMap()
    private var keywordReminderIntervals: Map<String, Int> = emptyMap()
    private var clusteringThresholdMinutes = 5
    private var usageTracker: KeywordUsageTracker? = null
    private var lastDetectedKeyword: String? = null
    private val lastReminderTimes = mutableMapOf<String, Long>()

    fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> {
        val regexes = mutableListOf<Regex>()
        val literals = mutableListOf<String>()
        for (kw in keywords) {
            val lower = kw.lowercase(Locale.ROOT)
            when {
                lower.startsWith("r:") ->
                    runCatching { Regex(lower.removePrefix("r:")) }.getOrNull()
                        ?.let { regexes.add(it) }
                lower.contains('*') || lower.contains('?') ->
                    regexes.add(wildcardToRegex(lower))
                else -> literals.add(lower)
            }
        }
        return regexes to literals
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val escaped = pattern
            .replace(Regex("""[.+^$()|\[\]{}\\]"""), """\\$0""")
            .replace("?", ".")
            .replace("*", ".*")
        val prefix = if (!pattern.startsWith("http") && !pattern.startsWith("*") &&
                        !pattern.startsWith("/") && !pattern.startsWith("?")) {
            """(?:https?://)?(?:www\.)?"""
        } else ""
        return Regex(prefix + escaped)
    }

    private fun matchesLiteral(keyword: String, urlIdentifier: String): Boolean {
        val url = urlIdentifier.lowercase(Locale.ROOT)
        val urlNoWww = url.removePrefix("www.")
        val kwNoWww = keyword.removePrefix("www.")

        if (url == keyword || urlNoWww == kwNoWww) return true
        if (url.startsWith("$keyword/") || url.startsWith("$keyword?") ||
            urlNoWww.startsWith("$kwNoWww/") || urlNoWww.startsWith("$kwNoWww?")) return true
        if (keyword.startsWith("/") && url.contains(keyword)) return true
        if (!keyword.contains('.') && !keyword.contains('/')) {
            val domain = url.substringBefore('/')
            if (domain.split('.').any { it == keyword }) return true
        }
        return false
    }

    private fun matchesPatterns(patterns: Pair<List<Regex>, List<String>>, urlIdentifier: String): Boolean {
        val lower = urlIdentifier.lowercase(Locale.ROOT)
        val (regexes, literals) = patterns
        return regexes.any { it.containsMatchIn(lower) } ||
               literals.any { matchesLiteral(it, urlIdentifier) }
    }

    private fun findMatchingGroup(urlIdentifier: String): KeywordGroup? {
        for (group in activeGroups) {
            val patterns = groupPatternMap[group.id] ?: continue
            if (matchesPatterns(patterns, urlIdentifier)) {
                return group
            }
        }
        return null
    }

    private fun matchesGroup(group: KeywordGroup, urlIdentifier: String): Boolean {
        val patterns = groupPatternMap[group.id] ?: return false
        return matchesPatterns(patterns, urlIdentifier)
    }

    fun isFocusWebsiteBlocked(
        packageName: String,
        compiledKeywords: Pair<List<Regex>, List<String>>,
        blockMode: FocusBlockMode
    ): Boolean {
        val date = TimeTools.getCurrentDate()
        val latest = runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForPackage(date, packageName)
                .maxByOrNull { it.lastVisited }
        } ?: return false

        if (latest.lastVisited < System.currentTimeMillis() - 5000) return false
        val urlIdentifier = latest.urlIdentifier.ifEmpty { return false }

        if (blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED && isInternalBrowserPage(urlIdentifier)) return false

        val matched = matchesPatterns(compiledKeywords, urlIdentifier)
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) matched else !matched
    }

    private fun isInternalBrowserPage(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("chrome://") || lower.startsWith("about:") ||
               lower.contains("newtab") || lower.contains("bookmarks") ||
               lower.contains("history") || lower.startsWith("search") ||
               lower.endsWith("url") || lower.contains("Search Google or type URL") ||
               !lower.contains('.') || lower.contains("null")
    }

    private fun containsBlockedKeyword(url: String): String? {
        val cacheKey = buildString {
            append(if (isSubstringMatchEnabled) "1|" else "0|")
            append(KeywordBlockerMatchUtils.normalizeBlockedEntry(url))
        }

        val cachedResult = detectionCache.get(cacheKey)
        if (cachedResult != null) {
            return if (cachedResult == SAFE_STRING_TOKEN) null else cachedResult
        }

        val matchedKeyword = KeywordBlockerMatchUtils.findBlockedEntry(
            input = url,
            blockedEntries = blockedKeywords,
            allowSubstringMatch = isSubstringMatchEnabled
        )
        if (matchedKeyword != null) {
            detectionCache.put(cacheKey, matchedKeyword)
            return matchedKeyword
        }

        detectionCache.put(cacheKey, SAFE_STRING_TOKEN)
        return null
    }

    private fun safeRecycle(node: AccessibilityNodeInfo?) {
        try { node?.recycle() } catch (_: Exception) {}
    }

    private fun safeRecycle(nodes: MutableList<AccessibilityNodeInfo>) {
        nodes.forEach { safeRecycle(it) }
        nodes.clear()
    }

    fun checkIfUserGettingFreaky(event: AccessibilityEvent?) {
        fun showMessage(word: String) {
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(
                    service,
                    service.getString(R.string.blocked_keyword_word_was_found).replace("-word", word),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun pressHome(word: String) {
            showMessage(word)
            Thread.sleep(300)
            service.pressHome()
        }

        if (!isTurnedOn) return
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        if (!service.isDelayOver(lastEventTimeStamp, refreshCooldown) || 
            event.packageName == "neth.iecal.curbox" || 
            ignoredApps.contains(event.packageName.toString())) {
            return
        }

        if (isUnsupportedBrowserBlockingOn && browserBlocker.isAppBrowser(event)) {
            return pressHome("/ unsupported browser")
        }

        val rootNode = service.rootInActiveWindow ?: return
        var detectedAdultKeyword: String? = null

        if (isSearchAllTextFields) {
            recursionResultNodes.clear()
            findNodesByClassName(rootNode, "android.widget.TextView", false)

            try {
                for (node in recursionResultNodes) {
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.isEmpty()) continue
                    val word = containsBlockedKeyword(nodeText)
                    if (word != null) {
                        detectedAdultKeyword = word
                        break
                    }
                }
            } catch (e: Exception) {
                AppLogger.functionError("KeywordBlocker", "checkIfUserGettingFreaky - searchAllTextFields", e)
            }
        }

        val urlBarInfo = URL_BAR_ID_LIST[event.packageName.toString()]
        if (urlBarInfo == null && detectedAdultKeyword != null) {
            lastEventTimeStamp = SystemClock.uptimeMillis()
            if (isTimeTrackingEnabled) {
                handleKeywordDetected(detectedAdultKeyword, event.packageName?.toString() ?: "")
                if (!isTimeLimitReached(detectedAdultKeyword)) {
                    safeRecycle(recursionResultNodes)
                    return
                }
            }
            pressHome(detectedAdultKeyword)
            safeRecycle(recursionResultNodes)
            return
        }

        if (urlBarInfo == null) {
            safeRecycle(recursionResultNodes)
            return
        }

        val idPrefixPart = event.packageName.toString() + ":id/"
        val displayUrlTextNode =
            ReelBlocker.findElementById(rootNode, idPrefixPart + urlBarInfo.displayUrlBarId)

        if (detectedAdultKeyword == null) {
            val webViewKeyword = searchKeywordsInWebViewTitle(rootNode)
            val displayText = displayUrlTextNode?.text?.toString() ?: ""

            detectedAdultKeyword = webViewKeyword ?: (if (displayText.isNotEmpty())
                containsBlockedKeyword(displayText)
            else null)
            
            if (detectedAdultKeyword == null) {
                safeRecycle(displayUrlTextNode)
                safeRecycle(recursionResultNodes)
                return
            }
        }

        val finalDetectedKeyword = detectedAdultKeyword
        lastEventTimeStamp = SystemClock.uptimeMillis()
        if (isTimeTrackingEnabled) {
            val packageName = event.packageName?.toString() ?: ""
            handleKeywordDetected(finalDetectedKeyword, packageName)
            if (!isTimeLimitReached(finalDetectedKeyword)) {
                safeRecycle(displayUrlTextNode)
                safeRecycle(recursionResultNodes)
                return
            }
        }

        performSmallUpwardScroll()
        Thread.sleep(200)
        displayUrlTextNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(200)

        val editUrlBarId = urlBarInfo.editUrlBarId ?: urlBarInfo.displayUrlBarId
        val editUrlBar = ReelBlocker.findElementById(rootNode, idPrefixPart + editUrlBarId)
            ?: run {
                pressHome(detectedAdultKeyword)
                safeRecycle(displayUrlTextNode)
                safeRecycle(recursionResultNodes)
                return
            }

        editUrlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, redirectUrl
            )
        })
        Thread.sleep(300)

        val didSubmitRedirect = submitEditedUrlBar(
            rootNode = rootNode,
            editUrlBar = editUrlBar,
            idPrefixPart = idPrefixPart,
            urlBarInfo = urlBarInfo
        )
        
        safeRecycle(editUrlBar)
        safeRecycle(displayUrlTextNode)
        safeRecycle(recursionResultNodes)

        if (!didSubmitRedirect) {
            return pressHome(detectedAdultKeyword)
        }

        Thread.sleep(2000)
    }

    private fun searchKeywordsInWebViewTitle(rootNode: AccessibilityNodeInfo): String? {
        recursionResultNodes.clear()
        try {
            findNodesByClassName(rootNode, "android.webkit.WebView")
        } catch (e: Exception) {
            AppLogger.functionError("KeywordBlocker", "searchKeywordsInWebViewTitle", e)
            return null
        }

        val webView = recursionResultNodes.getOrNull(0) ?: return null
        val titleText = webView.text?.toString() ?: ""
        if (titleText.isEmpty()) {
            safeRecycle(recursionResultNodes)
            return null
        }

        val result = containsBlockedKeyword(titleText)
        safeRecycle(recursionResultNodes)
        return result
    }

    private fun submitEditedUrlBar(
        rootNode: AccessibilityNodeInfo,
        editUrlBar: AccessibilityNodeInfo,
        idPrefixPart: String,
        urlBarInfo: BrowserUrlBarInfo
    ): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val imeEnterActionId = AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
            val supportsImeEnter = editUrlBar.actionList.any { it.id == imeEnterActionId }
            if (supportsImeEnter && editUrlBar.performAction(imeEnterActionId)) {
                return true
            }
        }

        val currentRootNode = service.rootInActiveWindow ?: rootNode
        val goBtnNode =
            ReelBlocker.findElementById(currentRootNode, idPrefixPart + urlBarInfo.browserSugggestionBoxId)
                ?: return false

        val didClickGo = if (urlBarInfo.isSuggestionEqualToGo) {
            goBtnNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            val child = goBtnNode.getChild(urlBarInfo.suggestionBoxIndexOfGoBtn)
            val result = child?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            safeRecycle(child)
            result
        }
        
        safeRecycle(goBtnNode)
        return didClickGo
    }

    private fun findNodesByClassName(
        node: AccessibilityNodeInfo?,
        targetClassName: String,
        returnOnFirstResult: Boolean = true
    ) {
        node ?: return

        if (node.className == targetClassName) {
            recursionResultNodes.add(AccessibilityNodeInfo.obtain(node))
        }

        if (returnOnFirstResult && recursionResultNodes.isNotEmpty()) return

        for (i in 0 until node.childCount) {
            findNodesByClassName(node.getChild(i), targetClassName, returnOnFirstResult)
            if (returnOnFirstResult && recursionResultNodes.isNotEmpty()) return
        }
    }

    fun performSmallUpwardScroll() {
        val path = Path()
        val screenHeight = Resources.getSystem().displayMetrics.heightPixels
        val startY = (screenHeight * 0.75).toFloat()
        val endY = startY - (screenHeight * 0.1).toFloat()
        val centerX = Resources.getSystem().displayMetrics.widthPixels / 2f

        path.moveTo(centerX, startY)
        path.lineTo(centerX, endY)

        val gestureBuilder = GestureDescription.Builder()
        val gestureStroke = GestureDescription.StrokeDescription(path, 0, 200)
        val gesture = gestureBuilder.addStroke(gestureStroke).build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCancelled(gestureDescription: GestureDescription?) {
                super.onCancelled(gestureDescription)
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            }
        }, null)
    }

    fun startObservingDatabase() {
        observationJob?.cancel()
        observationJob = CoroutineScope(Dispatchers.IO).launch {
            val db = AppDatabase.getInstance(service)
            val dao = db.websiteStatsDao()
            callbackFlow {
                val observer = object : InvalidationTracker.Observer("website_stats") {
                    override fun onInvalidated(tables: Set<String>) { trySend(Unit) }
                }
                db.invalidationTracker.addObserver(observer)
                awaitClose { db.invalidationTracker.removeObserver(observer) }
            }.collect {
                val date = TimeTools.getCurrentDate()
                val latest = dao.getStatsForDate(date).maxByOrNull { it.lastVisited }
                if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 2500)) {
                    evaluateAndBlock(latest)
                }
            }
        }
    }

    private fun evaluateAndBlock(entry: WebsiteStatsEntity) {
        val matchedGroup = findMatchingGroup(entry.urlIdentifier) ?: return

        val cooldownEnd = cooldownGroupsList[matchedGroup.id]
        if (cooldownEnd != null) {
            if (cooldownEnd > System.currentTimeMillis()) return
            else removeCooldownFrom(matchedGroup.id)
        }

        if (isBlocked(matchedGroup, entry.packageName)) {
            handleBlocking(matchedGroup)
        } else {
            calculateAndSetNextRecheck(matchedGroup, entry.packageName)
        }
    }

    private fun handleBlocking(group: KeywordGroup) {
        service.pressBack()
        Thread.sleep(1000)
        service.pressHome()
        Handler(Looper.getMainLooper()).postDelayed({
            val intent = Intent(service, WarningActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                putExtra("result_id", group.id)
                putExtra("warning_config", Gson().toJson(group.warningScreenConfig))
            }
            service.startActivity(intent)
        }, 300)
    }

    private fun isBlocked(group: KeywordGroup, packageName: String): Boolean =
        if (group.blockingType == AppBlockingType.Timed) isTimedBlockActive(group)
        else isUsageLimitExceeded(group, packageName)

    private fun isTimedBlockActive(group: KeywordGroup): Boolean {
        val config = Gson().fromJson(group.setting, AppTimeConfig::class.java) ?: return false
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals
                        else config.dailyIntervals[dayOfWeek] ?: emptyList()

        for (interval in intervals) {
            val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
            val withinAllowed = if (start <= end) currentMinutes in start until end
                                else currentMinutes >= start || currentMinutes < end
            if (withinAllowed) return false
        }
        return true
    }

    private fun isUsageLimitExceeded(group: KeywordGroup, packageName: String): Boolean {
        val config = Gson().fromJson(group.setting, AppUsageConfig::class.java) ?: return false
        val limit = (if (config.isDailyUniform) config.uniformLimit else {
            config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        }) * 60_000L

        if (limit <= 0) return true

        val date = TimeTools.getCurrentDate()
        val totalUsage = runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForPackage(date, packageName)
                .filter { matchesGroup(group, it.urlIdentifier) }
                .sumOf { it.totalTime }
        }
        return totalUsage >= limit
    }

    private fun calculateAndSetNextRecheck(group: KeywordGroup, packageName: String) {
        val now = System.currentTimeMillis()
        var nextRecheck = 0L

        if (group.blockingType == AppBlockingType.Usage) {
            val config = Gson().fromJson(group.setting, AppUsageConfig::class.java)
            if (config != null) {
                val limit = (if (config.isDailyUniform) config.uniformLimit else {
                    config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
                }) * 60_000L
                if (limit > 0) {
                    val date = TimeTools.getCurrentDate()
                    val totalUsage = runBlocking(Dispatchers.IO) {
                        AppDatabase.getInstance(service).websiteStatsDao()
                            .getStatsForPackage(date, packageName)
                            .filter { matchesGroup(group, it.urlIdentifier) }
                            .sumOf { it.totalTime }
                    }
                    val remaining = limit - totalUsage
                    if (remaining > 0) nextRecheck = now + remaining + 1000
                }
            }
        }

        if (group.blockingType == AppBlockingType.Timed) {
            val config = Gson().fromJson(group.setting, AppTimeConfig::class.java)
            if (config != null) {
                val calendar = Calendar.getInstance()
                val currentMinutes = TimeTools.convertToMinutesFromMidnight(
                    calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
                )
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
                val intervals = if (config.isEveryday) config.everydayIntervals
                                else config.dailyIntervals[dayOfWeek] ?: emptyList()

                var minMinutesUntilEnd = Int.MAX_VALUE
                for (interval in intervals) {
                    val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
                    val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
                    val withinAllowed = if (start <= end) currentMinutes in start until end
                                        else currentMinutes >= start || currentMinutes < end
                    if (withinAllowed) {
                        val minutesUntilEnd = if (start <= end || currentMinutes < end) end - currentMinutes
                                              else (1440 - currentMinutes) + end
                        minMinutesUntilEnd = minOf(minMinutesUntilEnd, minutesUntilEnd)
                    }
                }
                if (minMinutesUntilEnd != Int.MAX_VALUE) {
                    val recheckAt = now + (minMinutesUntilEnd * 60_000L) -
                        (calendar.get(Calendar.SECOND) * 1000L) - calendar.get(Calendar.MILLISECOND)
                    if (nextRecheck == 0L || recheckAt < nextRecheck) nextRecheck = recheckAt
                }
            }
        }

        if (nextRecheck > now) {
            CoroutineScope(Dispatchers.IO).launch {
                service.dataStoreManager.updateNextWebsiteRecheckTime(nextRecheck)
            }
        }
    }

    private var configJob: Job? = null

    fun setupBlocker(service: BaseBlockingService, watchSettings: Boolean = true) {
        this.service = service
        this.browserBlocker = BrowserBlocker(service)
        this.usageTracker = KeywordUsageTracker(service)
        usageTracker?.checkAndResetIfNewDay()
        this.prefs = service.getSharedPreferences("keyword_blocker_prefs", Context.MODE_PRIVATE)
        loadPersistedData()

        if (!watchSettings) return

        configJob?.cancel()
        configJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val config = settings.keywordBlockerConfig
                isTurnedOn = config.isActive
                isUnsupportedBrowserBlockingOn = config.blockAllExceptSupported
                browserBlocker.isTurnedOn = isTurnedOn

                activeGroups = if (isTurnedOn) {
                    config.keywordGroups.filter { it.isActive }
                } else emptyList()

                groupPatternMap = activeGroups.associate { group ->
                    group.id to compileKeywords(group.selectedKeywords)
                }.toMutableMap()

                blockedKeywords = activeGroups.flatMap { it.selectedKeywords }
                    .map(KeywordBlockerMatchUtils::normalizeBlockedEntry)
                    .filter { it.isNotBlank() }
                    .distinct()
                
                isSearchAllTextFields = config.searchRecursively
                redirectUrl = config.redirectUrl
                isSubstringMatchEnabled = config.matchSubstrings
                ignoredApps = config.ignoredApps.toHashSet()
                isTimeTrackingEnabled = config.isTimeTrackingEnabled
                keywordTimeLimits = config.keywordTimeLimits
                keywordReminderIntervals = config.keywordReminderIntervals
                clusteringThresholdMinutes = config.clusteringThresholdMinutes

                detectionCache.evictAll()
                if (isTurnedOn) startObservingDatabase() else observationJob?.cancel()
            }
        }
    }

    private fun loadPersistedData() {
        val keys = prefs.getStringSet("cooldown_keys", setOf()) ?: setOf()
        keys.forEach { id ->
            val end = prefs.getLong("cooldown_$id", 0L)
            if (end > System.currentTimeMillis()) cooldownGroupsList[id] = end
        }
    }

    private fun persistCooldownData() {
        prefs.edit {
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
            cooldownGroupsList.forEach { (id, end) -> putLong("cooldown_$id", end) }
        }
    }

    private fun removeCooldownFrom(id: String) {
        cooldownGroupsList.remove(id)
        prefs.edit {
            remove("cooldown_$id")
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
        }
    }

    private fun handleCooldownIntent(intent: Intent) {
        val groupId = intent.getStringExtra("result_id") ?: return
        val duration = intent.getIntExtra("selected_time", 120000)
        cooldownGroupsList[groupId] = System.currentTimeMillis() + duration
        persistCooldownData()

        val date = TimeTools.getCurrentDate()
        CoroutineScope(Dispatchers.IO).launch {
            val latest = AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date).maxByOrNull { it.lastVisited }
            if (latest != null && latest.lastVisited > (System.currentTimeMillis() - 5000)) {
                evaluateAndBlock(latest)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_CONFIG)
            addAction(INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun removeReceivers() {
        service.unregisterReceiver(refreshReceiver)
        observationJob?.cancel()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_CONFIG -> setupBlocker(service)
                INTENT_ACTION_REFRESH_KEYWORD_BLOCKER_COOLDOWN -> handleCooldownIntent(intent)
            }
        }
    }

    data class BrowserUrlBarInfo(
        val displayUrlBarId: String,
        val editUrlBarId: String? = null,
        val browserSugggestionBoxId: String,
        val suggestionBoxIndexOfGoBtn: Int = 0,
        val isSuggestionEqualToGo: Boolean = false
    )

    private fun handleKeywordDetected(keyword: String, packageName: String) {
        val tracker = usageTracker ?: return
        tracker.checkAndResetIfNewDay()
        tracker.recordDetection(keyword, packageName)
        lastDetectedKeyword = keyword

        val timeLimit = keywordTimeLimits[keyword] ?: 0
        val reminderInterval = keywordReminderIntervals[keyword] ?: 5
        val clusteringThresholdMs = clusteringThresholdMinutes * 60 * 1000L
        val currentUsageSeconds = tracker.calculateTotalUsageTimeForToday(keyword, clusteringThresholdMs)
        val usageMinutes = currentUsageSeconds / 60.0

        if (timeLimit > 0 && usageMinutes >= timeLimit) return
        if (reminderInterval > 0) checkAndShowReminder(keyword, usageMinutes, reminderInterval)
    }

    private fun checkAndShowReminder(keyword: String, currentUsageMinutes: Double, reminderInterval: Int) {
        val currentTime = System.currentTimeMillis()
        val reminderIntervalMs = reminderInterval * 60 * 1000L
        val lastReminderTime = lastReminderTimes[keyword] ?: 0L

        if (currentTime - lastReminderTime < reminderIntervalMs) return

        Handler(Looper.getMainLooper()).post {
            val message = "You've been using blocked keyword '$keyword' for ${currentUsageMinutes.toLong()} minutes"
            Toast.makeText(service, message, Toast.LENGTH_LONG).show()
        }
        lastReminderTimes[keyword] = currentTime
    }

    fun getTodayUsageMinutes(keyword: String): Double {
        val tracker = usageTracker ?: return 0.0
        val clusteringThresholdMs = clusteringThresholdMinutes * 60 * 1000L
        return tracker.calculateTotalUsageMinutesForToday(keyword, clusteringThresholdMs)
    }

    fun getTimeLimitForKeyword(keyword: String): Int {
        return keywordTimeLimits[keyword] ?: 0
    }

    fun isTimeLimitReached(keyword: String): Boolean {
        val timeLimit = keywordTimeLimits[keyword] ?: 0
        if (timeLimit <= 0) return false
        return getTodayUsageMinutes(keyword) >= timeLimit
    }
}
