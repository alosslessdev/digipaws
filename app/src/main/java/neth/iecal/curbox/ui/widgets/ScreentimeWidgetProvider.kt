package neth.iecal.curbox.ui.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.widget.RemoteViews
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.TimeTools
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.getDefaultLauncherPackageName

class ScreentimeWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "ScreentimeWidgetProvider"
        private const val ACTION_WIDGET_REFRESH = "neth.iecal.curbox.screentime.WIDGET_REFRESH"
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        try {
            appWidgetIds.forEach { widgetId ->
                updateWidget(context, appWidgetManager, widgetId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widgets", e)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        try {
            when (intent.action) {
                ACTION_WIDGET_REFRESH -> handleRefresh(context, intent)
                else -> Log.d(TAG, "Received unhandled action: ${intent.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling widget receive", e)
        }
    }

    private fun handleRefresh(context: Context, intent: Intent) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
            ?: appWidgetManager.getAppWidgetIds(ComponentName(context, ScreentimeWidgetProvider::class.java))

        widgetIds.forEach { widgetId ->
            updateWidget(context, appWidgetManager, widgetId)
        }
    }

    private fun updateWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        widgetId: Int
    ) {
        val usageStatsHelper = UsageStatsHelper(context)
        val ignoredPackages = mutableSetOf<String>()
        getDefaultLauncherPackageName(context.packageManager)?.let { ignoredPackages.add(it) }

        val ignoredApps = runBlocking {
            DataStoreManager(context).settings.first().usageTrackerIgnoredApps
        }
        ignoredPackages.addAll(ignoredApps)

        val list = runBlocking { usageStatsHelper.getForegroundStatsByRelativeDay(0) }.filter {
            it.totalTime >= 1_000 && it.packageName !in ignoredPackages
        }

        val totalScreentime = list.sumOf { it.totalTime }

        try {
            val views = RemoteViews(context.packageName, R.layout.widget_app_stats).apply {
                setTextViewText(R.id.screentime_widget, formatTime(totalScreentime))

                setAppUsageText(this, 0, list, R.id.app_1_sm, context)
                setAppUsageText(this, 1, list, R.id.app_2_sm, context)
                setAppUsageText(this, 2, list, R.id.app_3_sm, context)

                val refreshIntent = createRefreshIntent(context, widgetId)
                val refreshPendingIntent = PendingIntent.getBroadcast(
                    context,
                    widgetId,
                    refreshIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.refresh_stats_screentime, refreshPendingIntent)

                val openIntent = Intent(context, FragmentActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
                }
                val openPendingIntent = PendingIntent.getActivity(
                    context,
                    widgetId,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_bg_app_stats, openPendingIntent)
            }

            appWidgetManager.updateAppWidget(widgetId, views)
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget $widgetId", e)
        }
    }

    private fun setAppUsageText(
        remoteViews: RemoteViews,
        index: Int,
        list: List<AllAppsUsageFragment.Stat>,
        textViewId: Int,
        context: Context
    ) {
        val item = list.getOrNull(index)
        if (item == null) {
            remoteViews.setTextViewText(textViewId, "")
            return
        }
        try {
            val usage = TimeTools.formatTimeForWidget(item.totalTime)
            val appName = context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(item.packageName, 0)
            )
            remoteViews.setTextViewText(textViewId, "$usage : $appName")
        } catch (e: Exception) {
            val usage = TimeTools.formatTimeForWidget(item.totalTime)
            remoteViews.setTextViewText(textViewId, "$usage : ${item.packageName}")
        }
    }

    private fun createRefreshIntent(context: Context, widgetId: Int): Intent {
        return Intent(context, ScreentimeWidgetProvider::class.java).apply {
            action = ACTION_WIDGET_REFRESH
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(widgetId))
        }
    }

    private fun formatTime(timeInMillis: Long): String {
        val hours = timeInMillis / (1000 * 60 * 60)
        val minutes = (timeInMillis % (1000 * 60 * 60)) / (1000 * 60)

        if (hours == 0L && minutes == 0L) return "0m"

        return buildString {
            if (hours > 0) append("${hours}h")
            if (minutes > 0) append(" ${minutes}m")
        }.trim()
    }
}
