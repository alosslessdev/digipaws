package neth.iecal.curbox

import android.app.Application
import com.google.android.material.color.DynamicColors
import neth.iecal.curbox.utils.AppLogger
import neth.iecal.curbox.utils.BridgeServiceManager

class Curbox: Application() {
  override fun onCreate() {
    DynamicColors.applyToActivitiesIfAvailable(this)
    AppLogger.init(this)

    // Start the communication bridge service
    BridgeServiceManager.ensureBridgeRunning(this)

    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    super.onCreate()
  }

  override fun onTerminate() {
    BridgeServiceManager.cleanup(this)
    super.onTerminate()
  }
}
