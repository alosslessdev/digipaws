package neth.iecal.curbox.utils

import android.content.Context
import java.io.File

/**
 * Cache-free, cross-process store for Curbox protection state.
 *
 * Do NOT replace this with SharedPreferences or DataStore: the blocking service runs in a
 * separate process (:app_blocker_service) and both SharedPrefs (per-process cache) and
 * MultiProcess DataStore (per-process instance + async propagation) silently lose cross-process
 * reads, which is exactly the bug that kept cancel->reopen letting users into Curbox. The
 * filesystem is the only shared source of truth here, so every call opens the file fresh.
 *
 * Three longs, one per line:
 *   0: lastBackPressTimeStamp  wall-clock ms of the most recent block action
 *   1: lastBlockTimestampSeen  dedup guard for new-block detection in the service
 *   2: curboxProtectionDeadline  wall-clock ms when the protection window ends (0 = none)
 */
object CurboxProtectionStore {

    private const val FILE_NAME = "curbox_protection_state.txt"
    private const val TMP_NAME = "curbox_protection_state.tmp"
    private val lock = Any()

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun readArray(context: Context): LongArray {
        return synchronized(lock) {
            try {
                val text = file(context).readText()
                val parts = text.trim().split('\n')
                LongArray(3) { i -> parts.getOrNull(i)?.trim()?.toLongOrNull() ?: 0L }
            } catch (e: Exception) {
                LongArray(3)
            }
        }
    }

    private fun writeArray(context: Context, values: LongArray) {
        synchronized(lock) {
            try {
                val f = file(context)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, TMP_NAME)
                tmp.writeText("${values[0]}\n${values[1]}\n${values[2]}")
                if (tmp.renameTo(f)) return
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getLastBackPressTimeStamp(context: Context): Long = readArray(context)[0]

    fun setLastBackPressTimeStamp(context: Context, value: Long) {
        synchronized(lock) {
            val arr = readArray(context)
            if (arr[0] == value) return
            arr[0] = value
            writeArray(context, arr)
        }
    }

    fun getLastBlockTimestampSeen(context: Context): Long = readArray(context)[1]

    fun setLastBlockTimestampSeen(context: Context, value: Long) {
        synchronized(lock) {
            val arr = readArray(context)
            if (arr[1] == value) return
            arr[1] = value
            writeArray(context, arr)
        }
    }

    fun getDeadline(context: Context): Long = readArray(context)[2]

    fun setDeadline(context: Context, value: Long) {
        synchronized(lock) {
            val arr = readArray(context)
            if (arr[2] == value) return
            arr[2] = value
            writeArray(context, arr)
        }
    }

    fun clearDeadline(context: Context) = setDeadline(context, 0L)
}