package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.UsageDayItem
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.UsageSettingsAdapter

abstract class BaseUsageSettingsFragment : BottomSheetDialogFragment() {

    companion object {
        const val ARG_INITIAL_CONFIG = "arg_initial_config"
        const val EXTRA_CONFIG_JSON = "config_json"
        const val EXTRA_CONFIG_TYPE = "config_type"
    }

    protected open val daysOfWeek = listOf(
        "Same Limit Everyday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    private val dayItems = mutableListOf<UsageDayItem>()
    private lateinit var adapter: UsageSettingsAdapter
    private lateinit var daysListContainer: RecyclerView

    private var initialConfig: AppUsageConfig? = null

    protected abstract fun inflateView(inflater: LayoutInflater, container: ViewGroup?): View
    protected abstract fun loadUsageConfig(): AppUsageConfig
    protected abstract fun saveUsageConfig(config: AppUsageConfig)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val root = inflateView(inflater, container)
        daysListContainer = root.findViewById(R.id.daysListContainer)
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()

        arguments?.getString(ARG_INITIAL_CONFIG)?.let { json ->
            try {
                val config = Gson().fromJson(json, AppUsageConfig::class.java)
                saveUsageConfig(config)
            } catch (e: Exception) { e.printStackTrace() }
        }

        populateFromConfig()
        captureInitialState()

        view.findViewById<View>(R.id.fab_done)?.setOnClickListener {
            confirmAndFinish()
        }

        setupBackPressHandling()
    }

    private fun captureInitialState() {
        initialConfig = getCurrentConfigFromUi()
    }

    private fun hasChanges(): Boolean {
        return getCurrentConfigFromUi() != initialConfig
    }

    private fun setupBackPressHandling() {
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (hasChanges()) {
                    showUnsavedChangesDialog()
                } else {
                    isEnabled = false
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun showUnsavedChangesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.unsaved_changes_dialog_title)
            .setMessage(R.string.unsaved_changes_dialog_message)
            .setPositiveButton(R.string.save) { _, _ ->
                confirmAndFinish()
            }
            .setNegativeButton(R.string.btn_discard) { _, _ ->
                requireActivity().finish()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun confirmAndFinish() {
        val config = getCurrentConfigFromUi()
        saveUsageConfig(config)
        val resultIntent = Intent().apply {
            putExtra(EXTRA_CONFIG_JSON, Gson().toJson(config))
            putExtra(EXTRA_CONFIG_TYPE, "usage")
        }
        requireActivity().setResult(android.app.Activity.RESULT_OK, resultIntent)
        requireActivity().finish()
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (hasChanges()) {
            persistConfig()
        }
        super.onDismiss(dialog)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).also {
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    private fun setupRecyclerView() {
        dayItems.clear()
        daysOfWeek.forEach { dayItems.add(UsageDayItem(it, false, 0, 0)) }

        adapter = UsageSettingsAdapter(
            dayItems,
            onUniformToggle = { isUniform -> handleUniformLimitToggle(isUniform) },
            onDisabledClick = {
                Toast.makeText(requireContext(), "Disable everyday to add granular changes", Toast.LENGTH_SHORT).show()
            }
        )
        daysListContainer.layoutManager = LinearLayoutManager(requireContext())
        daysListContainer.adapter = adapter
    }

    private fun populateFromConfig() {
        val config = loadUsageConfig()

        dayItems[0].isEnabled = config.isDailyUniform
        dayItems[0].hours = (config.uniformLimit / 60).toInt()
        dayItems[0].minutes = (config.uniformLimit % 60).toInt()

        // dayItems 1-7 = Mon-Sun; dailyLimits[1-6] = Mon-Sat, dailyLimits[0] = Sun
        for (i in 1..6) setDayItem(i, config.dailyLimits[i])
        setDayItem(7, config.dailyLimits[0])

        handleUniformLimitToggle(config.isDailyUniform)
        adapter.notifyDataSetChanged()
    }

    private fun setDayItem(itemIndex: Int, minutesLimit: Long) {
        val item = dayItems[itemIndex]
        item.isEnabled = minutesLimit > 0
        item.hours = (minutesLimit / 60).toInt()
        item.minutes = (minutesLimit % 60).toInt()
    }

    private fun handleUniformLimitToggle(isUniform: Boolean) {
        dayItems.drop(1).forEach { it.isInteractionEnabled = !isUniform }
        if (isUniform) dayItems.drop(1).forEach { it.isEnabled = false }
        adapter.notifyItemRangeChanged(1, dayItems.size - 1)
    }

    private fun getCurrentConfigFromUi(): AppUsageConfig {
        val isDailyUniform = dayItems[0].isEnabled
        val config = AppUsageConfig(
            isDailyUniform = isDailyUniform,
            uniformLimit = if (isDailyUniform) (dayItems[0].hours * 60 + dayItems[0].minutes).toLong() else 0L
        )

        if (!isDailyUniform) {
            for (i in 1..6) config.dailyLimits[i] = (dayItems[i].hours * 60 + dayItems[i].minutes).toLong()
            config.dailyLimits[0] = (dayItems[7].hours * 60 + dayItems[7].minutes).toLong()
        }

        return config
    }

    private fun persistConfig() {
        saveUsageConfig(getCurrentConfigFromUi())
    }
}
