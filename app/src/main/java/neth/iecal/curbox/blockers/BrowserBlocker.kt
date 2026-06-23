package neth.iecal.curbox.blockers

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.net.toUri
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.utils.AppLogger

class BrowserBlocker(val service: AccessibilityService) : BaseBlocker() {

    private val cacheBlockedBrowserApps: HashSet<String> = hashSetOf()
    private val cacheNotBlockedBrowserApps: HashSet<String> = hashSetOf()

    var isTurnedOn = false
    
    fun isAppBrowser(event: AccessibilityEvent?): Boolean {
        if(!isTurnedOn || event == null) return false
        val packageName = event.packageName?.toString() ?: return false

        if (cacheBlockedBrowserApps.contains(packageName)) return true
        if (cacheNotBlockedBrowserApps.contains(packageName)) return false

        val isBrowser = resolveIsBrowser(service, packageName) && !URL_BAR_ID_LIST.containsKey(packageName)

        if (isBrowser) cacheBlockedBrowserApps.add(packageName)
        else cacheNotBlockedBrowserApps.add(packageName)

        return isBrowser
    }

    private fun resolveIsBrowser(context: Context, packageName: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, "http://www.curbox.life".toUri())
        intent.setPackage(packageName)
        val pm = context.packageManager
        val activities = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        AppLogger.logDebug("BrowserBlocker", "packages $activities")
        return activities.isNotEmpty()
    }
}
