package neth.iecal.curbox.trackers

import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.KeywordBlocker
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.WebsiteStatsDao
import neth.iecal.curbox.data.db.WebsiteStatsEntity
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.utils.AccessibilityHelper
import neth.iecal.curbox.utils.TimeTools
import java.util.regex.Pattern
import kotlin.text.endsWith
import kotlin.text.substring

class WebsiteUsageTracker {
    private lateinit var service: BaseBlockingService
    private lateinit var websiteStatsDao: WebsiteStatsDao
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var currentPackage: String? = null
    private var currentDomain: String? = null
    private var currentUrlIdentifier: String? = null
    private var domainStartTimeMs: Long = 0L

    private var recheckJob: Job? = null

    fun setup(service: BaseBlockingService) {
        this.service = service
        val db = AppDatabase.getInstance(service)
        this.websiteStatsDao = db.websiteStatsDao()
        startObservingRecheckTime()
    }

    private fun startObservingRecheckTime() {
        scope.launch {
            service.dataStoreManager.settings.collect { settings ->
                val nextRecheck = settings.nextWebsiteRecheckTime
                if (nextRecheck > System.currentTimeMillis()) {
                    scheduleRecheck(nextRecheck)
                }
            }
        }
    }

    private fun scheduleRecheck(recheckTime: Long) {
        recheckJob?.cancel()
        recheckJob = scope.launch {
            val delayMs = recheckTime - System.currentTimeMillis()
            if (delayMs > 0) {
                kotlinx.coroutines.delay(delayMs)
                Log.d("WebsiteUsageTracker", "Executing scheduled recheck")
                saveSession()
            }
        }
    }

    private fun filterOutUrlFromPlainText(inputText: String?): String? {
        if (inputText.isNullOrBlank()) return null

        val urlRegex = """(?:https?://|www\.)?[a-zA-Z0-9][a-zA-Z0-9\-]{1,61}[a-zA-Z0-9]\.[a-zA-Z]{2,}(?:[/\?#][a-zA-Z0-9\-._~:/?#\[\]@!${'$'}&'()*+,;=%]*)?"""
        val pattern = Pattern.compile(urlRegex, Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(inputText)

        if (matcher.find()) {
            var cleanUrl = matcher.group(0) ?: return null

            // Strip trailing punctuation unlikely to be part of the URL
            cleanUrl = cleanUrl.trimEnd('.', ',', ')', ']', '\'', '"', '>')

            return cleanUrl
        }

        return null
    }
    fun onEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        val packageName = event.packageName?.toString() ?: return
        
        if (!URL_BAR_ID_LIST.containsKey(packageName)) {
            // Not a supported browser package
            if (currentPackage != null) {
                saveSession()
                currentPackage = null
                currentDomain = null
                currentUrlIdentifier = null
            }
            return
        }

        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) {
            return
        }

        val rootNode = service.rootInActiveWindow ?: return
        val urlBarInfo = URL_BAR_ID_LIST[packageName] ?: return


        Log.d("source node",event.source.toString())
        try {
            val nodes = AccessibilityHelper.findElementById(
                rootNode,
                urlBarInfo.displayUrlBarId
            ) ?: AccessibilityHelper.findElementById(
                event.source,
                urlBarInfo.displayUrlBarId
            ) ?: return
            Log.d("found node",nodes.toString())
            val text = (nodes.text ?: nodes.contentDescription).toString()

            Log.d("website", text)
            if (text.isNotEmpty()) {
                val filteredUrl = filterOutUrlFromPlainText(text)
                val siteInfo = extractSiteInfo(filteredUrl?:text)
                if (siteInfo.domain.isNotEmpty()) {
                    if (siteInfo.urlIdentifier != currentUrlIdentifier || packageName != currentPackage) {
                        saveSession()
                        currentDomain = siteInfo.domain
                        currentUrlIdentifier = siteInfo.urlIdentifier
                        currentPackage = packageName
                        domainStartTimeMs = SystemClock.uptimeMillis()
                        saveInitialSession()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("WebsiteUsageTracker", "Failed to find node", e)
        }
    }

    private data class SiteInfo(val domain: String, val urlIdentifier: String)

    private fun extractSiteInfo(urlText: String): SiteInfo {
        return try {
            var url = urlText
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://$url"
            }
            val uri = java.net.URI(url)
            val domain = uri.host?.lowercase() ?: urlText
            
            // Extract identifier (domain + path segment)
            val identifier = if (uri.path.isNullOrEmpty()) domain else "$domain${uri.path}"
            
            SiteInfo(domain, identifier)
        } catch (e: Exception) {
            SiteInfo(urlText, urlText)
        }
    }


    private fun saveInitialSession() {
        val domain = currentDomain
        val identifier = currentUrlIdentifier
        val packageName = currentPackage

        if (domain != null && identifier != null && packageName != null) {
            val date = TimeTools.getCurrentDate()
            scope.launch {
                try {
                    val stat = websiteStatsDao.getStat(date, packageName, identifier)
                    websiteStatsDao.upsert(
                        WebsiteStatsEntity(
                            date = date,
                            packageName = packageName,
                            urlIdentifier = identifier,
                            domain = domain,
                            totalTime = stat?.totalTime ?: 0L,
                            lastVisited = System.currentTimeMillis()
                        )
                    )
                } catch (e: Exception) {
                    Log.e("WebsiteUsageTracker", "Failed to save initial website trace", e)
                }
            }
        }
    }

    private fun saveSession() {
        val domain = currentDomain
        val identifier = currentUrlIdentifier
        val packageName = currentPackage
        val startTime = domainStartTimeMs

        if (domain != null && identifier != null && packageName != null && startTime > 0) {
            val durationMs = SystemClock.uptimeMillis() - startTime
            if (durationMs > 1000) {
                Log.d("saved session", "$identifier -> $durationMs")
                val date = TimeTools.getCurrentDate()
                scope.launch {
                    try {
                        val stat = websiteStatsDao.getStat(date, packageName, identifier)
                        val totalTime = (stat?.totalTime ?: 0L) + durationMs
                        websiteStatsDao.upsert(
                            WebsiteStatsEntity(
                                date = date,
                                packageName = packageName,
                                urlIdentifier = identifier,
                                domain = domain,
                                totalTime = totalTime,
                                lastVisited = System.currentTimeMillis()
                            )
                        )
                    } catch (e: Exception) {
                        Log.e("WebsiteUsageTracker", "Failed to save website trace", e)
                    }
                }
            }
        }
    }

    fun onDestroy() {
        recheckJob?.cancel()
        saveSession()
    }
}
