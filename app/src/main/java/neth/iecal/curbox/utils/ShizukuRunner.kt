package neth.iecal.curbox.utils
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * Utility class for executing shell commands using Shizuku
 */
class ShizukuRunner {

    interface CommandResultListener {
        fun onCommandResult(output: String, done: Boolean) {}
        fun onCommandError(error: String) {}
    }

    companion object {
        fun executeCommand(command: String, listener: CommandResultListener, lineBundle: Int = 50) {
            if (!Shizuku.pingBinder()) {
                listener.onCommandError("Shizuku binder not available")
                return
            }

            Thread {
                try {
                    val binder = Shizuku.getBinder()
                    if (binder == null) {
                        listener.onCommandError("Shizuku binder is null")
                        return@Thread
                    }

                    val process = IShizukuService.Stub.asInterface(binder)
                        .newProcess(arrayOf("sh", "-c", command), null, null)

                    val outputReader = BufferedReader(InputStreamReader(FileInputStream(process.inputStream.fileDescriptor)))
                    val errorReader = BufferedReader(InputStreamReader(FileInputStream(process.errorStream.fileDescriptor)))

                    val outputBuffer = StringBuilder()
                    val errorBuffer = StringBuilder()

                    var line: String?
                    var lineCount = 0

                    while (outputReader.readLine().also { line = it } != null) {
                        lineCount++
                        outputBuffer.append(line).append("\n")

                        if (lineCount == lineBundle) {
                            lineCount = 0
                            listener.onCommandResult(outputBuffer.toString(), false)
                            outputBuffer.clear()
                        }
                    }

                    while (errorReader.readLine().also { line = it } != null) {
                        errorBuffer.append(line).append("\n")
                    }

                    if (errorBuffer.isNotBlank()) {
                        listener.onCommandError(errorBuffer.toString())
                    } else {
                        listener.onCommandResult(outputBuffer.toString(), true)
                    }

                    process.waitFor()

                } catch (e: Exception) {
                    listener.onCommandError(e.message ?: "An unexpected error occurred while executing the command.")
                }
            }.start()
        }
    }
}
