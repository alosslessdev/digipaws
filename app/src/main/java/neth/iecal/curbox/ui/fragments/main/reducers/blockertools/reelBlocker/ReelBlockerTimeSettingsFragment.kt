package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.ReelTimeConfig
import neth.iecal.curbox.databinding.FragmentReelBlockerTimeSettingsBinding
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment

class ReelBlockerTimeSettingsFragment : BaseTimeSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "reel_blocker_time_settings"
    }

    private val viewModel: ReelBlockerViewModel by activityViewModels()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentReelBlockerTimeSettingsBinding.inflate(inflater, container, false).root

    override fun getTimeConfig(): AppTimeConfig {
        val c = viewModel.getReelTimeConfig()
        return AppTimeConfig(c.isEveryday, c.everydayIntervals, c.dailyIntervals)
    }

    override fun saveTimeConfig(config: AppTimeConfig) {
        viewModel.saveReelTimeConfig(ReelTimeConfig(config.isEveryday, config.everydayIntervals, config.dailyIntervals))
    }
}
