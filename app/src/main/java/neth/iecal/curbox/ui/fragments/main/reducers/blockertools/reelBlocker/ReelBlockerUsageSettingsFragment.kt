package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.ReelUsageConfig
import neth.iecal.curbox.databinding.FragmentReelBlockerUsageSettingsBinding
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseUsageSettingsFragment

class ReelBlockerUsageSettingsFragment : BaseUsageSettingsFragment() {

    companion object {
        const val FRAGMENT_ID = "ReelBlockerUsageSettingsBottomSheet"
    }

    private val viewModel: ReelBlockerViewModel by activityViewModels()

    override fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View =
        FragmentReelBlockerUsageSettingsBinding.inflate(inflater, container, false).root

    override fun loadUsageConfig(): AppUsageConfig {
        val c = viewModel.getReelUsageConfig()
        return AppUsageConfig(c.isDailyUniform, c.uniformLimit).also { c.dailyLimits.copyInto(it.dailyLimits) }
    }

    override fun saveUsageConfig(config: AppUsageConfig) {
        val reelConfig = ReelUsageConfig(config.isDailyUniform, config.uniformLimit)
        config.dailyLimits.copyInto(reelConfig.dailyLimits)
        viewModel.saveReelUsageConfig(reelConfig)
    }
}
