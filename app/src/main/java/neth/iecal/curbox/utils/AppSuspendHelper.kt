package neth.iecal.curbox.utils

import android.content.Context
<<<<<<< HEAD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
=======
import android.content.Intent
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
import rikka.shizuku.Shizuku
import neth.iecal.curbox.data.models.FocusBlockMode
import android.util.Log

object AppSuspendHelper {

<<<<<<< HEAD
    private var scope: CoroutineScope? = null

    fun init(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

=======
    // Todo: Sometimes user start focus mode, apps get suspended but in midst of that, they turn off shizuku. This creates a forever suspend bug
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
    fun suspendApps(packages: List<String>) {
        executePmCommand(packages, "suspend")
    }

    fun unsuspendApps(packages: List<String>) {
        executePmCommand(packages, "unsuspend")
    }

    fun unsuspendAllApps(context: Context) {
        if (!isShizukuAvailable()) return
        scope?.launch(Dispatchers.IO) {
            try {
                val allPackages = getInstalledPackagesSafe(context)
                executePmCommand(allPackages, "unsuspend")
            } catch (e: Exception) {
<<<<<<< HEAD
                AppLogger.functionError("AppSuspendHelper", "unsuspendAllApps", e)
=======
                Log.e("AppSuspendHelper", "Failed to unsuspend all apps", e)
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
            }
        }
    }

    fun getPackagesToSuspend(
        context: Context,
        blockMode: FocusBlockMode,
        groupPackages: Set<String>,
        essentialPackages: Set<String>
    ): List<String> {
        val realPackages = groupPackages.filter { !it.startsWith("webapp:") }.toSet()
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) {
            realPackages.toList()
        } else {
<<<<<<< HEAD
            val allPackages = context.packageManager.getInstalledPackages(0).map { it.packageName }
            allPackages.filter { it !in realPackages && it !in essentialPackages }
=======
            val allPackages = getInstalledPackagesSafe(context)
            allPackages.filter { it !in groupPackages && it !in essentialPackages }
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
        }
    }

    private fun getInstalledPackagesSafe(context: Context): List<String> {
        return try {
            context.packageManager.getInstalledPackages(0).map { it.packageName }
        } catch (e: Exception) {
            Log.w("AppSuspendHelper", "getInstalledPackages failed, falling back to queryIntentActivities", e)
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            context.packageManager.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName }
                .distinct()
        }
    }

    private fun executePmCommand(packages: List<String>, commandType: String) {
        if (!isShizukuAvailable() || packages.isEmpty()) return
        scope?.launch(Dispatchers.IO) {
            packages.chunked(40).forEach { chunk ->
                val command = "pm $commandType ${chunk.joinToString(" ")}"
                ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                    override fun onCommandError(error: String) {
                        AppLogger.logError("AppSuspendHelper", "Command error: $error")
                    }
                })
            }
        } ?: run {
            // Fallback for when scope is not initialized (not ideal)
            Thread {
                packages.chunked(40).forEach { chunk ->
                    val command = "pm $commandType ${chunk.joinToString(" ")}"
                    ShizukuRunner.executeCommand(command, object : ShizukuRunner.CommandResultListener {
                        override fun onCommandError(error: String) {}
                    })
                }
            }.start()
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }
}
