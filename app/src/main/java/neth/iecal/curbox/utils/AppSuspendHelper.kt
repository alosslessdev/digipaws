package neth.iecal.curbox.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import neth.iecal.curbox.data.models.FocusBlockMode

object AppSuspendHelper {

    private var scope: CoroutineScope? = null

    fun init(coroutineScope: CoroutineScope) {
        scope = coroutineScope
    }

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
                val allPackages = context.packageManager.getInstalledPackages(0).map { it.packageName }
                executePmCommand(allPackages, "unsuspend")
            } catch (e: Exception) {
                AppLogger.functionError("AppSuspendHelper", "unsuspendAllApps", e)
            }
        }
    }

    fun getPackagesToSuspend(
        context: Context,
        blockMode: FocusBlockMode,
        groupPackages: Set<String>,
        essentialPackages: Set<String>
    ): List<String> {
        return if (blockMode == FocusBlockMode.BLOCK_SELECTED) {
            groupPackages.toList()
        } else {
            val allPackages = context.packageManager.getInstalledPackages(0).map { it.packageName }
            allPackages.filter { it !in groupPackages && it !in essentialPackages }
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
