package neth.iecal.curbox.blockers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
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
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_CLICKED or
                    AccessibilityEvent.TYPE_VIEW_FOCUSED

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

        val cachedResult = detectionCache.get(normalizedInput)
        if (cachedResult != null) {
            return if (cachedResult == SAFE_STRING_TOKEN) null else cachedResult
        }

        val matchedKeyword = KeywordBlockerMatchUtils.findBlockedEntry(
            input = normalizedInput,
            blockedEntries = blockedKeywords
        )
        if (matchedKeyword != null) {
            detectionCache.put(normalizedInput, matchedKeyword)
            return matchedKeyword
        }

        detectionCache.put(normalizedInput, SAFE_STRING_TOKEN)
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
        val delayOver = service.isDelayOver(1000)
        Log.d(TAG, "pressHome for $word. delayOver: $delayOver")

        showMessage(word)
        service.pressHome()

        if (group != null && delayOver) {
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

    private fun findUrlBarNode(packageName: String, info: BrowserUrlBarInfo): AccessibilityNodeInfo? {
        val id = "$packageName:id/${info.displayUrlBarId}"

        // 1. Try rootInActiveWindow
        val root = service.rootInActiveWindow
        if (root != null) {
            val node = ReelBlocker.findElementById(root, id)
            if (node != null) {
                if (node.packageName != packageName) {
                    safeRecycle(node)
                } else {
                    safeRecycle(root)
                    return node
                }
            }
            safeRecycle(root)
        }

        // 2. Try all windows
        for (window in service.windows) {
            val windowRoot = window.root
            if (windowRoot != null) {
                val node = ReelBlocker.findElementById(windowRoot, id)
                if (node != null) {
                    if (node.packageName != packageName) {
                        safeRecycle(node)
                    } else {
                        safeRecycle(windowRoot)
                        return node
                    }
                }
                safeRecycle(windowRoot)
            }
        }

        return null
    }

    fun checkIfUnsupportedBrowser(event: AccessibilityEvent?) {
        checkIfUserGettingFreaky(event)
    }

    fun checkIfUserGettingFreaky(event: AccessibilityEvent?) {
        var packageName = event?.packageName?.toString() ?: return

        // Use a more robust way to get the active package if systemui/android is reporting
        if (packageName == "com.android.systemui" || packageName == "android") {
            val root = service.rootInActiveWindow
            val rootPkg = root?.packageName?.toString()
            if (rootPkg != null && rootPkg != "neth.iecal.curbox" && rootPkg != "com.android.systemui" && rootPkg != "android") {
                packageName = rootPkg
            }
            safeRecycle(root)
        }

        if (!isTurnedOn) return

        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        // Log all events from supported browsers to see what's happening
        if (URL_BAR_ID_LIST.containsKey(packageName) || packageName == "neth.iecal.curbox") {
            Log.d(TAG, "checkIfUserGettingFreaky: event from $packageName, type: ${AccessibilityEvent.eventTypeToString(event.eventType)}")
        }

        if (packageName == "neth.iecal.curbox") {
            if (!service.isDelayOver(1000)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    val intent = Intent(service, WarningActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("mode", Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER)
                        putExtra("result_id", "neth.iecal.curbox")
                        putExtra("warning_config", Gson().toJson(AppBlockerWarningScreenConfig(
                            message = "Wait a moment before opening Curbox right after a block.",
                            proceedDelayInSecs = 5
                        )))
                    }
                    service.startActivity(intent)
                }, 100)
            }
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastEventTimeStamp < refreshCooldown ||
            !service.isDelayOver(1000) ||
            ignoredApps.contains(packageName)) {
            return
        }

        if (isUnsupportedBrowserBlockingOn && browserBlocker.isAppBrowser(event)) {
            lastEventTimeStamp = currentTime
            return pressHome("/ unsupported browser")
        }

        val urlBarInfo = URL_BAR_ID_LIST[packageName]
        val idPrefixPart = "$packageName:id/"

        var detectedKeyword: String? = null
        var displayUrlTextNode: AccessibilityNodeInfo? = null

        // 1. Try to find URL bar in the active window or all windows
        if (urlBarInfo != null) {
            displayUrlTextNode = findUrlBarNode(packageName, urlBarInfo)

            val displayText = displayUrlTextNode?.text?.toString() ?: ""
            if (displayText.isNotEmpty()) {
                detectedKeyword = containsBlockedKeyword(displayText)
                if (detectedKeyword != null) {
                    Log.d(TAG, "Detected keyword in URL bar: $detectedKeyword ('$displayText')")
                }
            }
        }

        // 2. If not found, try recursive search if enabled
        if (detectedKeyword == null && isSearchAllTextFields) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                recursionResultNodes.clear()
                findNodesByClassName(rootNode, "android.widget.TextView", false)
                for (node in recursionResultNodes) {
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.isEmpty()) continue
                    val word = containsBlockedKeyword(nodeText)
                    if (word != null) {
                        detectedKeyword = word
                        Log.d(TAG, "Detected keyword via recursive search: $detectedKeyword")
                        break
                    }
                }
                safeRecycle(rootNode)
            }
        }

        // 3. Try WebView title if still not found
        if (detectedKeyword == null && urlBarInfo != null) {
            val rootNode = service.rootInActiveWindow
            if (rootNode != null) {
                detectedKeyword = searchKeywordsInWebViewTitle(rootNode)
                if (detectedKeyword != null) {
                    Log.d(TAG, "Detected keyword in WebView title: $detectedKeyword")
                }
                safeRecycle(rootNode)
            }
        }

        // 4. Try the event source directly as a last resort
        if (detectedKeyword == null) {
            val source = event.source
            if (source != null) {
                val sourceText = source.text?.toString() ?: ""
                if (sourceText.isNotEmpty()) {
                    detectedKeyword = containsBlockedKeyword(sourceText)
                    if (detectedKeyword != null) {
                        Log.d(TAG, "Detected keyword in event source: $detectedKeyword")
                    }
                }
                source.recycle()
            }
        }

        if (detectedKeyword == null) {
            safeRecycle(displayUrlTextNode)
            safeRecycle(recursionResultNodes)
            return
        }

        Log.d(TAG, "DETECTED BLOCKED KEYWORD: $detectedKeyword")
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

        Thread.sleep(250) //we need three waits for the Chrome ui to update the blocked word in the address bar
        // so that the user is not locked out of the browser
        service.pressBack()//if the user presses a Chrome home screen shortcut, this will cause Chrome to exit
        //to the home screen, if the user is typing a blocked word it will close the keyboard
        Thread.sleep(250)
        service.pressBack()//we need a second back press on chrome so that the user is able to type
        //another URL
        Thread.sleep(250)
        pressHome(keyword, matchedGroup)

        safeRecycle(displayUrlTextNode)
        safeRecycle(recursionResultNodes)
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
            val child = node.getChild(i)
            findNodesByClassName(child, targetClassName, returnOnFirstResult)
            safeRecycle(child)
            if (returnOnFirstResult && recursionResultNodes.isNotEmpty()) return
        }
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

        Log.d(TAG, "Evaluating website from DB: ${entry.urlIdentifier}")

        // Give UI thread a small head start for supported browsers
        if (URL_BAR_ID_LIST.containsKey(entry.packageName)) {
            Thread.sleep(200)
            if (SystemClock.uptimeMillis() - lastEventTimeStamp < 2000) return
        }

        val matchedGroup = findMatchingGroup(entry.urlIdentifier)
        val keyword = containsBlockedKeyword(entry.urlIdentifier) ?: entry.urlIdentifier
        val isBlockedByLimit = isTimeTrackingEnabled && isTimeLimitReached(keyword)

        if (matchedGroup == null && !isBlockedByLimit) {
            Log.d(TAG, "No block action for ${entry.urlIdentifier} (no matching active group or limit reached)")
            return
        }

        if (matchedGroup != null) {
            val cooldownEnd = cooldownGroupsList[matchedGroup.id]
            if (cooldownEnd != null) {
                if (cooldownEnd > System.currentTimeMillis()) return
                else removeCooldownFrom(matchedGroup.id)
            }
        }

        if ((matchedGroup != null && isBlocked(matchedGroup, entry.packageName)) || isBlockedByLimit) {
            handleBlocking(matchedGroup ?: KeywordGroup(id = "global_limit", name = "Global Limit", selectedKeywords = listOf(keyword)), keyword, entry.packageName)
        }

        if (matchedGroup != null) {
            computeNextRecheck(matchedGroup)
        }
    }

    private fun handleBlocking(group: KeywordGroup, word: String, packageName: String) {
        if (!service.isDelayOver(1000)) return

        if (URL_BAR_ID_LIST.containsKey(packageName)) {
            // If it's a browser, redirection should be handled by UI thread.
            // We only proceed here if the UI thread lock has expired or wasn't set.
            if (SystemClock.uptimeMillis() - lastEventTimeStamp < 2000) return
        }

        Thread.sleep(250) //we need three waits for the Chrome ui to update the blocked word in the address bar
        // so that the user is not locked out of the browser
        service.pressBack()//if the user presses a Chrome home screen shortcut, this will cause Chrome to exit
        //to the home screen, if the user is typing a blocked word it will close the keyboard
        Thread.sleep(250)
        service.pressBack()//we need a second back press on chrome so that the user is able to type
        //another URL
        Thread.sleep(250)
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
            AppBlockingType.Usage -> isUsageLimitExceeded(group)
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

    private fun isUsageLimitExceeded(group: KeywordGroup): Boolean {
        val config = Gson().fromJson(group.setting, AppUsageConfig::class.java) ?: return false
        val limit = (if (config.isDailyUniform) config.uniformLimit else {
            config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
        }) * 60_000L

        if (limit <= 0) return true

        return groupUsage(group) >= limit
    }

    // Combined usage of every keyword in the group across all browsers, so the
    // limit applies to the group as a whole rather than each browser separately.
    private fun groupUsage(group: KeywordGroup): Long {
        val date = TimeTools.getCurrentDate()
        return runBlocking(Dispatchers.IO) {
            AppDatabase.getInstance(service).websiteStatsDao()
                .getStatsForDate(date)
                .filter { matchesGroup(group, it.urlIdentifier) }
                .sumOf { it.totalTime }
        }
    }

    // Returns when this group should next be re-checked (0 if no re-check is needed). The caller is
    // responsible for persisting the soonest value across all matched groups.
    private fun computeNextRecheck(group: KeywordGroup): Long {
        val now = System.currentTimeMillis()
        var nextRecheck = 0L

        if (group.blockingType == AppBlockingType.Usage) {
            val config = Gson().fromJson(group.setting, AppUsageConfig::class.java)
            if (config != null) {
                val limit = (if (config.isDailyUniform) config.uniformLimit else {
                    config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
                }) * 60_000L
                if (limit > 0) {
                    val remaining = limit - groupUsage(group)
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
        return nextRecheck
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
                Log.d(TAG, "KeywordBlocker settings updated. isActive: $isTurnedOn")
                isUnsupportedBrowserBlockingOn = config.blockAllExceptSupported
                browserBlocker.isTurnedOn = isTurnedOn

                activeGroups = if (isTurnedOn) {
                    config.keywordGroups.filter { it.isActive }
                } else emptyList()

                groupPatternMap = activeGroups.associate { group ->
                    group.id to compileKeywords(group.selectedKeywords)
                }.toMutableMap()

                blockedKeywords = activeGroups.flatMap { group ->
                    group.selectedKeywords.flatMap { kw ->
                        val normalized = KeywordBlockerMatchUtils.normalizeBlockedEntry(kw)
                        val domain = if ("." in normalized) normalized.substringBefore(".") else ""
                        if (domain.length > 3) listOf(normalized, domain) else listOf(normalized)
                    }
                }.filter { it.isNotBlank() }.distinct()

                Log.d(TAG, "KeywordBlocker configured. Active: $isTurnedOn, Groups: ${activeGroups.size}, Keywords: $blockedKeywords")

                isSearchAllTextFields = config.searchRecursively ?: true
                redirectUrl = config.redirectUrl.ifBlank { "https://curbox.life" }
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

    fun isTimeLimitReached(keyword: String): Boolean {
        val timeLimit = keywordTimeLimits[keyword] ?: 0
        if (timeLimit <= 0) return false
        return getTodayUsageMinutes(keyword) >= timeLimit
    }
}
