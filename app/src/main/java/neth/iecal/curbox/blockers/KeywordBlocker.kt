package neth.iecal.curbox.blockers

import neth.iecal.curbox.R

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Resources
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.utils.KeywordBlockerMatchUtils

class KeywordBlocker : BaseBlocker() {
    companion object {
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
            // Todo; Fix firefox redirector not working because fails to access the edittext
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

        // CONSTANTS FOR CACHING
        private const val SAFE_STRING_TOKEN = "||SAFE||"

        const val INTENT_ACTION_REFRESH_CONFIG =
            "neth.iecal.curbox.refresh.keywordblocker.config"
        private const val TARGET_EVENTS_MASK = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED

    }
    private lateinit var service : BaseBlockingService
    private lateinit var browserBlocker : BrowserBlocker

    private var blockedKeywords: List<String> = emptyList()
    private var redirectUrl: String = ""
    var isSearchAllTextFields = false
    private var isSubstringMatchEnabled = false
    var recursionResultNodes: MutableList<AccessibilityNodeInfo> = mutableListOf()

    // Caches the results of string evaluations. Max 200 items to prevent memory bloat.
    // Maps the raw text -> The blocked keyword found (or SAFE_STRING_TOKEN if safe)
    private val detectionCache = LruCache<String, String>(200)
    private var isTurnedOn = false
    private var isUnsupportedBrowserBlockingOn = false
    private var ignoredApps: HashSet<String> = hashSetOf()
    private var settingsJob: Job? = null

    private var lastEventTimeStamp = 0L
    private var refreshCooldown : Int = 2000


    private fun containsBlockedKeyword(url: String): String? {
        val cacheKey = buildString {
            append(if (isSubstringMatchEnabled) "1|" else "0|")
            append(KeywordBlockerMatchUtils.normalizeBlockedEntry(url))
        }

        // Check cache first
        val cachedResult = detectionCache.get(cacheKey)
        if (cachedResult != null) {
            return if (cachedResult == SAFE_STRING_TOKEN) null else cachedResult
        }
        AppLogger.logDebug("KeywordBlocker", "checking $url")

        val matchedKeyword = KeywordBlockerMatchUtils.findBlockedEntry(
            input = url,
            blockedEntries = blockedKeywords,
            allowSubstringMatch = isSubstringMatchEnabled
        )
        if (matchedKeyword != null) {
            detectionCache.put(cacheKey, matchedKeyword)
            return matchedKeyword
        }

        // Cache as safe and return null
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
                    service.getString(R.string.blocked_keyword_word_was_found).replace("-word",word),
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        fun pressHome(word: String) {
            showMessage(word)
            Thread.sleep(300)
            service.pressHome()
        }

        if(!isTurnedOn) return
        if (event == null || (event.eventType and TARGET_EVENTS_MASK) == 0) return

        if (!service.isDelayOver(
                lastEventTimeStamp,
                refreshCooldown
            ) || event.packageName == "neth.iecal.curbox" || ignoredApps.contains(
                event.packageName
            )
        ) {
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
                recursionResultNodes.forEach { node ->
                    val nodeText = node.text?.toString() ?: ""
                    if (nodeText.isEmpty()) return@forEach
                    val word = containsBlockedKeyword(nodeText)
                    if (word != null) {
                        detectedAdultKeyword = word
                        return@forEach // breaks from the forEach loop
                    }
                }
            } catch (e: Exception) {
                AppLogger.functionError("KeywordBlocker", "checkIfUserGettingFreaky - searchAllTextFields", e)
            }
        }

        val urlBarInfo = URL_BAR_ID_LIST[event.packageName]
        if (urlBarInfo == null && detectedAdultKeyword != null) {
            pressHome(detectedAdultKeyword!!)
            return
        }

        if (urlBarInfo == null) return

        val idPrefixPart = event.packageName.toString() + ":id/"
        val displayUrlTextNode =
            ReelBlocker.findElementById(rootNode, idPrefixPart + urlBarInfo.displayUrlBarId)

        if (detectedAdultKeyword == null) {
            val webViewKeyword = searchKeywordsInWebViewTitle(rootNode)
            val displayText = displayUrlTextNode?.text?.toString() ?: ""

            detectedAdultKeyword = webViewKeyword ?: (if (displayText.isNotEmpty())
                containsBlockedKeyword(displayText)
            else null) ?: return
        }

        performSmallUpwardScroll()
        Thread.sleep(200)
        displayUrlTextNode?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        Thread.sleep(200)

        val editUrlBarId = urlBarInfo.editUrlBarId ?: urlBarInfo.displayUrlBarId
        val editUrlBar = ReelBlocker.findElementById(rootNode, idPrefixPart + editUrlBarId)
            ?: return pressHome(detectedAdultKeyword!!)

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
        if (!didSubmitRedirect) {
            return pressHome(detectedAdultKeyword!!)
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
        if (titleText.isEmpty()) return null

        return containsBlockedKeyword(titleText)
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
                AppLogger.logDebug("KeywordBlocker", "Submitted redirect via IME enter")
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
            goBtnNode.getChild(urlBarInfo.suggestionBoxIndexOfGoBtn)
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }

        if (didClickGo) {
            AppLogger.logDebug("KeywordBlocker", "Submitted redirect via browser go button")
        }
        return didClickGo
    }

    private fun findNodesByClassName(
        node: AccessibilityNodeInfo?,
        targetClassName: String,
        returnOnFirstResult: Boolean = true
    ) {
        node ?: return

        if (node.className == targetClassName) {
            recursionResultNodes.add(node)
        }

        for (i in 0 until node.childCount) {
            findNodesByClassName(node.getChild(i), targetClassName)
        }
        if (returnOnFirstResult && recursionResultNodes.isNotEmpty()) return
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
                service.performGlobalAction(GLOBAL_ACTION_HOME)
            }
        }, null)
    }

    fun setupBlocker(service: BaseBlockingService){
        this.service = service
        this.browserBlocker = BrowserBlocker(service)
        AppLogger.logDebug("KeywordBlocker", "Setting up kw blocker")
        settingsJob?.cancel()
        settingsJob = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                val config = settings.keywordBlockerConfig
                val normalizedKeywords = config.blockedKeywords
                    .map(KeywordBlockerMatchUtils::normalizeBlockedEntry)
                    .filter { it.isNotBlank() }
                    .distinct()

                val shouldClearCache =
                    normalizedKeywords != blockedKeywords ||
                            isSearchAllTextFields != config.searchRecursively ||
                            redirectUrl != config.redirectUrl ||
                            isSubstringMatchEnabled != config.matchSubstrings ||
                            isUnsupportedBrowserBlockingOn != config.blockAllExceptSupported ||
                            isTurnedOn != config.isActive ||
                            ignoredApps != config.ignoredApps.toHashSet()

                blockedKeywords = normalizedKeywords
                isSearchAllTextFields = config.searchRecursively
                redirectUrl = config.redirectUrl
                isSubstringMatchEnabled = config.matchSubstrings
                isUnsupportedBrowserBlockingOn = config.blockAllExceptSupported
                isTurnedOn = config.isActive
                ignoredApps = config.ignoredApps.toHashSet()
                browserBlocker.isTurnedOn = isUnsupportedBrowserBlockingOn

                if (shouldClearCache) {
                    detectionCache.evictAll()
                }
            }
        }

    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers(){
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_CONFIG)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }


    fun removeReceivers(){
        service.unregisterReceiver(refreshReceiver)
    }
    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                INTENT_ACTION_REFRESH_CONFIG -> {
                    detectionCache.evictAll()
                    setupBlocker(service)
                }
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
}
