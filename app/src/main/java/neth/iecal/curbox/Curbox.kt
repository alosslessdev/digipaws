package neth.iecal.curbox

import android.app.Application
import com.google.android.material.color.DynamicColors

import neth.iecal.curbox.utils.AppLogger

class Curbox: Application() {
  override fun onCreate() {
    AppLogger.init(this)
    DynamicColors.applyToActivitiesIfAvailable(this)
    Thread.setDefaultUncaughtExceptionHandler(CrashLogger(this))
    super.onCreate()
  }
}
