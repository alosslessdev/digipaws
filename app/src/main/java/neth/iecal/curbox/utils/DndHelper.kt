package neth.iecal.curbox.utils

import android.app.NotificationManager
import android.content.Context

object DndHelper {
    private const val PREFS_NAME = "DndPrefs"
    private const val KEY_WAS_TURNED_ON_BY_US = "was_turned_on_by_us"

    private fun getPrefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun applyDndState(context: Context, shouldBeOn: Boolean) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return
        
        val currentFilter = nm.currentInterruptionFilter
        val prefs = getPrefs(context)
        val wasTurnedOnByUs = prefs.getBoolean(KEY_WAS_TURNED_ON_BY_US, false)

        if (shouldBeOn) {
            if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                prefs.edit().putBoolean(KEY_WAS_TURNED_ON_BY_US, true).apply()
            }
        } else {
            // Only turn off if we were the ones who turned it on
            if (wasTurnedOnByUs && currentFilter != NotificationManager.INTERRUPTION_FILTER_ALL) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
                prefs.edit().putBoolean(KEY_WAS_TURNED_ON_BY_US, false).apply()
            }
        }
    }
}
