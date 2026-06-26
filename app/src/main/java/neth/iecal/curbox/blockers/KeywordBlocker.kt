package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
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
import android.util.Log
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
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
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
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
        private const val TAG = "KeywordBlocker"
    }

    private lateinit var service: BaseBlockingService
    private lateinit var browserBlocker: BrowserBlocker
    private lateinit var prefs: SharedPreferences

    private var activeGroups = listOf<KeywordGroup>()
    private var groupPatternMap = mutableMapOf<String, Pair<List<Regex>, List<String>>>()

    private val detectionCache = LruCache<String, String>(200)
    private var isTurnedOn = false
    private var isUnsupportedBrowserBlockingOn = false
    private var cooldownGroupsList = HashMap<String, Long>()
    private var observationJob: Job? = null

    private var blockedKeywords: List<String> = emptyList()
    private var redirectUrl: String = "https://curbox.life"
    var isSearchAllTextFields = false
    private var isSubstringMatchEnabled = false
    var recursionResultNodes: MutableList<AccessibilityNodeInfo> = mutableListOf()
    private var ignoredApps: HashSet<String> = hashSetOf()
    private var configJob: Job? = null

    @Volatile
    private var lastEventTimeStamp = 0L
    private var refreshCooldown : Int = 1000

    private var isTimeTrackingEnabled = false
    private var keywordTimeLimits: Map<String, Int> = emptyMap()
    private var keywordReminderIntervals: Map<String, Int> = emptyMap()
    private var clusteringThresholdMinutes = 5
    private var usageTracker: KeywordUsageTracker? = null
    private val lastReminderTimes = mutableMapOf<String, Long>()

    fun compileKeywords(keywords: Collection<String>): Pair<List<Regex>, List<String>> {
        val regexes = mutableListOf<Regex>()
        val literals = mutableListOf<String>()
        for (kw in keywords) {
            val trimmed = kw.trim()
            when {
                trimmed.startsWith("r:", ignoreCase = true) ->
                    runCatching { Regex(trimmed.removePrefix("r:"), RegexOption.IGNORE_CASE) }.getOrNull()
                        ?.let { regexes.add(it) }
                trimmed.contains('*') || trimmed.contains('?') ->
                    regexes.add(wildcardToRegex(trimmed))
                else -> literals.add(trimmed.lowercase(Locale.ROOT))
            }
        }
        return regexes to literals
    }

    private fun wildcardToRegex(pattern: String): Regex {
        val escaped = pattern.lowercase(Locale.ROOT)
            .replace(Regex("""[.+^$()|\[\]{}\\]"""), """\\$0""")
            .replace("?", ".")
            .replace("*", ".*")
        val prefix = if (!pattern.startsWith("http", ignoreCase = true) &&
                        !pattern.startsWith("*") &&
                        !pattern.startsWith("/") &&
                        !pattern.startsWith("?")) {
            """(?:https?://)?(?:www\.)?"""
        } else ""
        return Regex(prefix + escaped, RegexOption.IGNORE_CASE)
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
        val normalizedInput = KeywordBlockerMatchUtils.normalizeBlockedEntry(urlIdentifier)
        val normalizedRedirect = KeywordBlockerMatchUtils.normalizeBlockedEntry(redirectUrl)
        if (normalizedInput == normalizedRedirect || normalizedInput.startsWith("$normalizedRedirect/")) return false

        val (regexes, literals) = patterns
        return regexes.any { it.containsMatchIn(urlIdentifier) } ||
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
        val normalizedInput = KeywordBlockerMatchUtils.normalizeBlockedEntry(url)
        if (normalizedInput.isEmpty() || isInternalBrowserPage(normalizedInput)) return null

        val normalizedRedirect = KeywordBlockerMatchUtils.normalizeBlockedEntry(redirectUrl)
        if (normalizedInput == normalizedRedirect || normalizedInput.startsWith("$normalizedRedirect/")) return null

        val cacheKey = buildString {
            append(if (isSubstringMatchEnabled) "1|" else "0|")
            append(normalizedInput)
        }

        val cachedResult = detectionCache.get(cacheKey)
        if (cachedResult != null) {
            return if (cachedResult == SAFE_STRING_TOKEN) null else cachedResult
        }

        val matchedKeyword = KeywordBlockerMatchUtils.findBlockedEntry(
            input = normalizedInput,
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

    private fun showMessage(word: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                service,
                service.getString(R.string.blocked_keyword_word_was_found).replace("-word", word),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun pressHome(word: String, group: KeywordGroup? = null) {
        showMessage(word)
        service.pressHome()
        if (group != null && service.isDelayOver(15000)) {
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
    }

    fun checkIfUserGettingFreaky(event: AccessibilityEvent?) {
        if (!isTurnedOn) return
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        val packageName = event.packageName?.toString() ?: return

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastEventTimeStamp < refreshCooldown || 
            !service.isDelayOver(15000) ||
            ignoredApps.contains(packageName)) {
            return
        }

        if (isUnsupportedBrowserBlockingOn && browserBlocker.isAppBrowser(event)) {
            lastEventTimeStamp = currentTime
            return pressHome("/ unsupported browser")
        }

        val rootNode = service.rootInActiveWindow ?: return
        var detectedKeyword: String? = null

        if (isSearchAllTextFields) {
            recursionResultNodes.clear()
            findNodesByClassName(rootNode, "android.widget.TextView", false)

            try {
                for (node in recursionResultNodes) {
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.isEmpty()) continue
                    val word = containsBlockedKeyword(nodeText)
                    if (word != null) {
                        detectedKeyword = word
                        break
                    }
                }
            } catch (e: Exception) {
                AppLogger.functionError(TAG, "checkIfUserGettingFreaky - searchAllTextFields", e)
            }
        }

        val urlBarInfo = URL_BAR_ID_LIST[packageName]
        val idPrefixPart = "$packageName:id/"
        
        var displayUrlTextNode: AccessibilityNodeInfo? = if (urlBarInfo != null)
            ReelBlocker.findElementById(rootNode, idPrefixPart + urlBarInfo.displayUrlBarId)
        else null

        // Bypass if we are already at the redirect URL
        if (displayUrlTextNode != null) {
            val displayText = displayUrlTextNode.text?.toString() ?: ""
            if (displayText.isNotEmpty()) {
                val normalizedCurrent = KeywordBlockerMatchUtils.normalizeBlockedEntry(displayText)
                val normalizedRedirect = KeywordBlockerMatchUtils.normalizeBlockedEntry(redirectUrl)
                if (normalizedCurrent == normalizedRedirect || normalizedCurrent.startsWith("$normalizedRedirect/")) {
                    safeRecycle(displayUrlTextNode)
                    safeRecycle(recursionResultNodes)
                    return
                }
            }
        }

        if (detectedKeyword == null && urlBarInfo != null) {
            val webViewKeyword = searchKeywordsInWebViewTitle(rootNode)
            val displayText = displayUrlTextNode?.text?.toString() ?: ""

            detectedKeyword = webViewKeyword ?: (if (displayText.isNotEmpty())
                containsBlockedKeyword(displayText)
            else null)
        }

        if (detectedKeyword == null) {
            safeRecycle(displayUrlTextNode)
            safeRecycle(recursionResultNodes)
            return
        }

        val keyword = detectedKeyword!!

        if (isTimeTrackingEnabled) {
            handleKeywordDetected(keyword, packageName)
        }

        var matchedGroup = getBlockingGroupForKeyword(keyword, packageName)
        val isBlockedByLimit = isTimeTrackingEnabled && isTimeLimitReached(keyword)

        if (matchedGroup == null && isBlockedByLimit) {
            // Find any group that contains this keyword to get a warning config if possible
            matchedGroup = activeGroups.firstOrNull { group ->
                val patterns = groupPatternMap[group.id] ?: return@firstOrNull false
                matchesPatterns(patterns, keyword)
            }
        }

        if (matchedGroup == null && !isBlockedByLimit) {
            safeRecycle(displayUrlTextNode)
            safeRecycle(recursionResultNodes)
            return
        }

        // Lock other blocking mechanisms immediately
        lastEventTimeStamp = SystemClock.uptimeMillis()

        if (urlBarInfo == null) {
            pressHome(keyword, matchedGroup)
            safeRecycle(displayUrlTextNode)
            safeRecycle(recursionResultNodes)
            return
        }

        // If it's a supported browser but we didn't find the URL bar, try one more time
        if (displayUrlTextNode == null) {
            Thread.sleep(100)
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                displayUrlTextNode = ReelBlocker.findElementById(freshRoot, idPrefixPart + urlBarInfo.displayUrlBarId)
                if (freshRoot != rootNode) safeRecycle(freshRoot)
            }
        }

        if (displayUrlTextNode == null) {
            pressHome(keyword, matchedGroup)
            safeRecycle(recursionResultNodes)
            return
        }

        performSmallUpwardScroll()
        Thread.sleep(250)
        displayUrlTextNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        
        var editUrlBar: AccessibilityNodeInfo? = null
        val editUrlBarId = urlBarInfo.editUrlBarId ?: urlBarInfo.displayUrlBarId
        
        // Try finding the edit bar for up to 1 second
        for (i in 1..5) {
            Thread.sleep(200)
            val freshRoot = service.rootInActiveWindow
            if (freshRoot != null) {
                editUrlBar = ReelBlocker.findElementById(freshRoot, idPrefixPart + editUrlBarId)
                if (editUrlBar != null) {
                    if (freshRoot != rootNode) safeRecycle(freshRoot)
                    break
                }
                safeRecycle(freshRoot)
            }
        }

        if (editUrlBar == null) {
            pressHome(keyword, matchedGroup)
            safeRecycle(displayUrlTextNode)
            safeRecycle(recursionResultNodes)
            return
        }

        editUrlBar.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, redirectUrl)
        editUrlBar.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        
        Thread.sleep(100)
        submitEditedUrlBar(
            rootNode = service.rootInActiveWindow ?: rootNode,
            editUrlBar = editUrlBar,
            idPrefixPart = idPrefixPart,
            urlBarInfo = urlBarInfo
        )
        
        safeRecycle(editUrlBar)
        safeRecycle(displayUrlTextNode)
        safeRecycle(recursionResultNodes)

        // Verify redirection
        var redirectionSuccessful = false
        for (i in 1..5) {
            Thread.sleep(300)
            val finalRoot = service.rootInActiveWindow ?: continue
            val finalUrlNode = ReelBlocker.findElementById(finalRoot, idPrefixPart + urlBarInfo.displayUrlBarId)
            val finalUrl = finalUrlNode?.text?.toString() ?: ""
            if (finalUrl.isNotEmpty() && containsBlockedKeyword(finalUrl) == null) {
                redirectionSuccessful = true
                safeRecycle(finalUrlNode)
                safeRecycle(finalRoot)
                break
            }
            safeRecycle(finalUrlNode)
            safeRecycle(finalRoot)
        }

        if (!redirectionSuccessful) {
            pressHome(keyword, matchedGroup)
        }
        lastEventTimeStamp = SystemClock.uptimeMillis()
    }

    private fun searchKeywordsInWebViewTitle(rootNode: AccessibilityNodeInfo): String? {
        recursionResultNodes.clear()
        try {
            findNodesByClassName(rootNode, "android.webkit.WebView")
        } catch (e: Exception) {
            Log.e(TAG, "searchKeywordsInWebViewTitle error", e)
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
            if (supportsImeEnter) {
                if (editUrlBar.performAction(imeEnterActionId)) {
                    return true
                }
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
        if (currentRootNode != rootNode) safeRecycle(currentRootNode)
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

        service.dispatchGesture(gesture, null, null)
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
        if (SystemClock.uptimeMillis() - lastEventTimeStamp < 2000) return
        
        // Give UI thread a small head start for supported browsers
        if (URL_BAR_ID_LIST.containsKey(entry.packageName)) {
            Thread.sleep(200)
            if (SystemClock.uptimeMillis() - lastEventTimeStamp < 2000) return
        }

        val matchedGroup = findMatchingGroup(entry.urlIdentifier) ?: return

        val cooldownEnd = cooldownGroupsList[matchedGroup.id]
        if (cooldownEnd != null) {
            if (cooldownEnd > System.currentTimeMillis()) return
            else removeCooldownFrom(matchedGroup.id)
        }

        if (isBlocked(matchedGroup, entry.packageName)) {
            val keyword = containsBlockedKeyword(entry.urlIdentifier) ?: entry.urlIdentifier
            handleBlocking(matchedGroup, keyword, entry.packageName)
        }
        calculateAndSetNextRecheck(matchedGroup, entry.packageName)
    }

    private fun handleBlocking(group: KeywordGroup, word: String, packageName: String) {
        if (!service.isDelayOver(15000)) return

        if (URL_BAR_ID_LIST.containsKey(packageName)) {
             // If it's a browser, redirection should be handled by UI thread.
             // We only proceed here if the UI thread lock has expired or wasn't set.
             if (SystemClock.uptimeMillis() - lastEventTimeStamp < 5000) return
        }

        service.pressBack()
        Thread.sleep(500)
        pressHome(word)
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
        when (group.blockingType) {
            AppBlockingType.Timed -> isTimedBlockActive(group)
            AppBlockingType.Usage -> isUsageLimitExceeded(group, packageName)
            AppBlockingType.OnOpen -> true
        }

    private fun getBlockingGroupForKeyword(keyword: String, packageName: String): KeywordGroup? {
        for (group in activeGroups) {
            val patterns = groupPatternMap[group.id] ?: continue
            if (matchesPatterns(patterns, keyword)) {
                if (isBlocked(group, packageName)) {
                    return group
                }
            }
        }
        return null
    }

    private fun isTimedBlockActive(group: KeywordGroup): Boolean {
        val config = Gson().fromJson(group.setting, AppTimeConfig::class.java) ?: return false
        val calendar = Calendar.getInstance()
        val currentMinutes = TimeTools.convertToMinutesFromMidnight(
            calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE)
        )
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) - 1
        val intervals = if (config.isEveryday) config.everydayIntervals
                        else config.dailyIntervals[dayOfWeek] ?: emptyList()

        if (intervals.isEmpty()) return true

        for (interval in intervals) {
            val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
            val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
            val withinInterval = if (start <= end) currentMinutes in start until end
                                 else currentMinutes >= start || currentMinutes < end
            if (withinInterval) return false
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

                var minMinutesUntilChange = Int.MAX_VALUE
                for (interval in intervals) {
                    val start = TimeTools.convertToMinutesFromMidnight(interval.startHour, interval.startMinute)
                    val end = TimeTools.convertToMinutesFromMidnight(interval.endHour, interval.endMinute)
                    
                    val minutesUntilChange = if (start <= end) {
                        if (currentMinutes in start until end) {
                            end - currentMinutes
                        } else if (currentMinutes < start) {
                            start - currentMinutes
                        } else {
                            (1440 - currentMinutes) + start
                        }
                    } else {
                        if (currentMinutes >= start || currentMinutes < end) {
                            if (currentMinutes >= start) (1440 - currentMinutes) + end
                            else end - currentMinutes
                        } else {
                            start - currentMinutes
                        }
                    }
                    minMinutesUntilChange = minOf(minMinutesUntilChange, minutesUntilChange)
                }

                if (minMinutesUntilChange != Int.MAX_VALUE) {
                    val recheckAt = now + (minMinutesUntilChange * 60_000L) -
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
                redirectUrl = config.redirectUrl.ifBlank { "https://curbox.life" }
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
        prefs.edit().apply {
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
            cooldownGroupsList.forEach { (id, end) -> putLong("cooldown_$id", end) }
        }.apply()
    }

    private fun removeCooldownFrom(id: String) {
        cooldownGroupsList.remove(id)
        prefs.edit().apply {
            remove("cooldown_$id")
            putStringSet("cooldown_keys", cooldownGroupsList.keys)
        }.apply()
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
        try {
            service.unregisterReceiver(refreshReceiver)
        } catch (_: Exception) {}
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
