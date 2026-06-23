package neth.iecal.curbox.trackers

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.RECEIVER_EXPORTED
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.graphics.Rect
import android.util.LruCache
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.blockers.ReelBlocker
import neth.iecal.curbox.blockers.uihider.NodeFinder
import neth.iecal.curbox.data.db.AppDatabase
import neth.iecal.curbox.data.db.ReelStatsDao
import neth.iecal.curbox.data.db.ReelStatsEntity
import neth.iecal.curbox.data.models.ReelAppData
import neth.iecal.curbox.data.models.ReelCounterOverlayConfig
import neth.iecal.curbox.hardcoded.ReelAppConfig.Companion.reelData
import neth.iecal.curbox.services.BaseBlockingService
import neth.iecal.curbox.ui.overlay.ReelsOverlayManager
import neth.iecal.curbox.utils.TimeTools



class ReelsCountTracker {

    companion object {
        const val INTENT_ACTION_REFRESH_REEL_COUNTER = "neth.iecal.curbox.refresh.reel_counter"
    }

    private lateinit var service: BaseBlockingService
    private lateinit var overlayManager: ReelsOverlayManager
    private lateinit var reelStatsDao: ReelStatsDao

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isOnDisplayCounter = true
    private var overlayConfig = ReelCounterOverlayConfig()
    private var todayCount = 0
    private var lastDateStr = TimeTools.getCurrentDate()


    private val lastDynamicText = mutableMapOf<String, String>()
    private val seenReelsCache = mutableMapOf<String, LruCache<String, Boolean>>()

    private var ignored = listOf<String>()
    fun setup(service: BaseBlockingService, overlayManager: ReelsOverlayManager) {
        this.service = service
        this.overlayManager = overlayManager

        ignored = listOf("com.android.systemui",
            service.packageName,
            "com.google.android.apps.wellbeing")
        val db = AppDatabase.getInstance(service)
        this.reelStatsDao = db.reelStatsDao()

        scope.launch {
            service.dataStoreManager.settings.collectLatest { settings ->
                isOnDisplayCounter = settings.isReelCounterOn
                overlayConfig = settings.reelCounterOverlayConfig
            }
        }

        scope.launch {
            try {
                lastDateStr = TimeTools.getCurrentDate()
                todayCount = reelStatsDao.getCount(lastDateStr) ?: 0
            } catch (_: Exception) {
                todayCount = 0
            }
        }
    }

    fun onEvent(event: AccessibilityEvent?) {

        if (event == null || ignored.contains(event.packageName.toString())) return

        try {
            val pkg = event.packageName?.toString() ?: return

            if (reelData.containsKey(pkg)) {
                if((event.eventType and reelData[pkg]!!.eventType) == 0) return
                if (Settings.canDrawOverlays(service) && !overlayManager.isOverlayVisible) {
                    overlayManager.reelsScrolledThisSession = todayCount
                    overlayManager.startDisplaying(overlayConfig, isOnDisplayCounter)
                }

                checkForReelProgression(pkg, reelData[pkg]!!)
            } else if (overlayManager.isOverlayVisible) {
                overlayManager.removeOverlay()
                return
            }


        } catch (_: Exception) { }
    }

    private fun checkForReelProgression(pkg: String, data: ReelAppData) {
        val root = service.rootInActiveWindow ?: return

        Log.d("reel","searchin view $data")

        val viewNode = NodeFinder.findFirst(root, data.viewId)
        Log.d("reel",viewNode.toString())

        if (viewNode == null || !isViewInBounds(viewNode)) {
            viewNode?.let { NodeFinder.recycle(it) }
            hideReelCounter()
            return
        }
        NodeFinder.recycle(viewNode)
        Log.d("reel","found view")

        // Check if required views are present
        for (req in data.requiresPresent) {
            val node = NodeFinder.findFirst(root, req)
            if (node == null || !isViewInBounds(node)) {
                node?.let { NodeFinder.recycle(it) }
                hideReelCounter()
                return
            }
            NodeFinder.recycle(node)
        }

        Log.d("reel","all present")

        // Check if requires absent views are found
        for (req in data.requiresAbsent) {
            val node = NodeFinder.findFirst(root, req)
            if (node != null && isViewInBounds(node)) {
                NodeFinder.recycle(node)
                hideReelCounter()
                return
            }
            node?.let { NodeFinder.recycle(it) }
        }
        Log.d("reel","all absent")

        // Loop dynamic comparator viewgroups and extract text
        var currentText = ""
        for (compId in data.dynamicComparator) {
            val compNode = NodeFinder.findFirst(root, compId)
            if (compNode != null) {
                currentText += data.comparsionResultCleanser( extractTextFromNode(compNode))
                NodeFinder.recycle(compNode)
            }
        }

        Log.d("reel_text",currentText)

        if (currentText.trim().isBlank()) return

        val previousText = lastDynamicText[pkg] ?: ""
        if (currentText != previousText) {
            val isSubstantialChange = isSubstantialTextChange(currentText, previousText)

            if (previousText.isNotEmpty() && isSubstantialChange) {
                val appCache = seenReelsCache.getOrPut(pkg) { LruCache(50) }
                if (appCache.get(currentText) == null) {
                    onReelCounted()
                    appCache.put(currentText, true)
                }
            }
            
            if (isSubstantialChange || currentText.length > previousText.length) {
                lastDynamicText[pkg] = currentText
            }
        }
    }

    private fun isViewInBounds(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val displayMetrics = service.resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        return rect.left < screenWidth && rect.right > 0 && rect.top < screenHeight && rect.bottom > 0
    }

    private fun extractTextFromNode(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""
        var result = ""
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (text != null) result += text
        if (desc != null) result += desc

        for (i in 0 until node.childCount) {
            result += extractTextFromNode(node.getChild(i))
        }
        return result
    }

    fun getTodayCount(): Int = todayCount

    private fun onReelCounted() {
        val date = TimeTools.getCurrentDate()
        if (date != lastDateStr) {
            todayCount = 0
            lastDateStr = date
        }
        todayCount++
        overlayManager.reelsScrolledThisSession = todayCount

        if (isOnDisplayCounter) {
            overlayManager.binding?.reelCounter?.apply {
                visibility = View.VISIBLE
                text = todayCount.toString()
            }
        } else {
            overlayManager.binding?.reelCounter?.visibility = View.GONE
        }

        scope.launch {
            try {
                reelStatsDao.upsert(ReelStatsEntity(date = date, count = todayCount))
            } catch (_: Exception) { }
        }

        service.lastBackPressTimeStamp = SystemClock.uptimeMillis()
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                INTENT_ACTION_REFRESH_REEL_COUNTER -> setup(service, overlayManager)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    fun setupReceivers() {
        val filter = IntentFilter().apply {
            addAction(INTENT_ACTION_REFRESH_REEL_COUNTER)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(refreshReceiver, filter, RECEIVER_EXPORTED)
        } else {
            service.registerReceiver(refreshReceiver, filter)
        }
    }

    fun onDestroy() {
        overlayManager.binding = null
        try { service.unregisterReceiver(refreshReceiver) } catch (_: Exception) {}
    }

    private fun hideReelCounter() {
        overlayManager.binding?.reelCounter?.visibility = View.GONE
    }

    private fun isSubstantialTextChange(currentText: String, previousText: String): Boolean {
        if (currentText.isEmpty() || previousText.isEmpty()) return true

        fun countWords(text: String, wordCounts: HashMap<String, Int>) {
            val len = text.length
            var start = -1
            for (i in 0 until len) {
                if (text[i].isWhitespace()) {
                    if (start != -1) {
                        val word = text.substring(start, i)
                        wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
                        start = -1
                    }
                } else {
                    if (start == -1) start = i
                }
            }
            if (start != -1) {
                val word = text.substring(start, len)
                wordCounts[word] = wordCounts.getOrDefault(word, 0) + 1
            }
        }

        val currentWords = HashMap<String, Int>()
        val previousWords = HashMap<String, Int>()
        
        countWords(currentText, currentWords)
        countWords(previousText, previousWords)

        if (currentWords.isEmpty() || previousWords.isEmpty()) return true

        var intersectionSize = 0
        var totalSmaller = 0
        
        val smallerMap = if (currentWords.size < previousWords.size) currentWords else previousWords
        val largerMap = if (currentWords.size < previousWords.size) previousWords else currentWords

        for ((word, count) in smallerMap) {
            totalSmaller += count
            val largerCount = largerMap[word] ?: 0
            intersectionSize += minOf(count, largerCount)
        }

        if (totalSmaller == 0) return true

        val overlapRatio = intersectionSize.toFloat() / totalSmaller
        return overlapRatio < 0.90f
    }
}
