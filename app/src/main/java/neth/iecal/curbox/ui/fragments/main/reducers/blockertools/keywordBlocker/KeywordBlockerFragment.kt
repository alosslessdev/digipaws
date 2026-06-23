package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import neth.iecal.curbox.R

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class KeywordBlockerFragment : Fragment() {

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    private var selectedApps = listOf<String>()
    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                viewModel.setIgnoredApps(apps)
                binding.btnSelectIgnoredApps.text = getString(R.string.select_ignored_apps) + " (${apps.size})"
                selectedApps = apps
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnAddKeyword.setOnClickListener {
            var keyword = binding.etKeyword.text.toString()
            if (keyword.isNotBlank()) {
                if (Patterns.WEB_URL.matcher(keyword).matches()) {
                    keyword = keyword
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .removePrefix("www.")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warning_link_blocker_may_not_work),
                        Toast.LENGTH_LONG
                    ).show()
                }
                viewModel.addKeyword(keyword)
                binding.etKeyword.setText("")
                showFocusGroupPicker(keyword)
            }
        }

        binding.etRedirectUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    viewModel.setRedirectUrl(s.toString())
                }
            }
        })

        binding.cbSearchRecursively.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setSearchRecursively(isChecked)
            }
        }

        binding.cbMatchSubstrings.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setMatchSubstrings(isChecked)
            }
        }

        binding.cbBlockUnsupportedBrowsers.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setBlockAllExceptSupported(isChecked)
            }
        }

        binding.btnSelectIgnoredApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedApps))
            selectAppsLauncher.launch(intent)
        }

        binding.switchTimeTracking.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setTimeTrackingEnabled(isChecked)
                binding.layoutTimeTrackingSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        binding.etClusteringThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    val threshold = s.toString().toIntOrNull() ?: 5
                    viewModel.setClusteringThreshold(threshold)
                }
            }
        })
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.etRedirectUrl.text.toString() != config.redirectUrl) {
                    binding.etRedirectUrl.setText(config.redirectUrl)
                }

                if (binding.cbSearchRecursively.isChecked != config.searchRecursively) {
                    binding.cbSearchRecursively.isChecked = config.searchRecursively
                }

                if (binding.cbMatchSubstrings.isChecked != config.matchSubstrings) {
                    binding.cbMatchSubstrings.isChecked = config.matchSubstrings
                }

                if (binding.cbBlockUnsupportedBrowsers.isChecked != config.blockAllExceptSupported) {
                    binding.cbBlockUnsupportedBrowsers.isChecked = config.blockAllExceptSupported
                }
                selectedApps = config.ignoredApps
                binding.btnSelectIgnoredApps.text = getString(R.string.select_ignored_apps) + " (${selectedApps.size})"

                if (binding.switchTimeTracking.isChecked != config.isTimeTrackingEnabled) {
                    binding.switchTimeTracking.isChecked = config.isTimeTrackingEnabled
                    binding.layoutTimeTrackingSettings.visibility = if (config.isTimeTrackingEnabled) View.VISIBLE else View.GONE
                }

                val currentThreshold = binding.etClusteringThreshold.text.toString().toIntOrNull() ?: 5
                if (currentThreshold != config.clusteringThresholdMinutes) {
                    binding.etClusteringThreshold.setText(config.clusteringThresholdMinutes.toString())
                }

                updateKeywordsList(config.blockedKeywords)

                isUpdatingUi = false
            }
        }
    }

    private fun updateKeywordsList(keywords: List<String>) {
        binding.cgKeywords.removeAllViews()
        val config = viewModel.keywordBlockerConfig.value
        for (keyword in keywords) {
            val focusGroupIds = config.keywordFocusGroups[keyword] ?: emptyList()
            val chip = Chip(requireContext()).apply {
                text = if (focusGroupIds.isNotEmpty()) {
                    val groupNames = focusGroupIds.mapNotNull { id ->
                        viewModel.focusGroups.value.find { it.groupId == id }?.groupName
                            ?: viewModel.autoFocusGroups.value.find { it.groupId == id }?.groupName
                    }
                    if (groupNames.isNotEmpty()) "$keyword (${groupNames.joinToString(", ")})" else keyword
                } else {
                    keyword
                }
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    showRemoveConfirmation(keyword)
                }
                setOnClickListener {
                    showKeywordTimeSettings(keyword)
                }
                setOnLongClickListener {
                    showFocusGroupPicker(keyword)
                    true
                }
            }
            binding.cgKeywords.addView(chip)
        }
    }

    private fun showFocusGroupPicker(keyword: String) {
        val selectedGroupIds = viewModel.getKeywordFocusGroups(keyword).toMutableSet()
        val allGroups: List<neth.iecal.curbox.data.models.FocusGroup> = viewModel.focusGroups.value + viewModel.autoFocusGroups.value

        val groupNames = allGroups.map { it.groupName }.toTypedArray()
        val checkedItems = BooleanArray(allGroups.size) { i ->
            selectedGroupIds.contains(allGroups[i].groupId)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Focus Groups for: $keyword")
            .setMultiChoiceItems(groupNames, checkedItems) { _, which, isChecked ->
                val groupId = allGroups[which].groupId
                if (isChecked) {
                    selectedGroupIds.add(groupId)
                } else {
                    selectedGroupIds.remove(groupId)
                }
            }
            .setPositiveButton(R.string.save) { _, _ ->
                viewModel.setKeywordFocusGroups(keyword, selectedGroupIds.toList())
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton("Clear") { _, _ ->
                viewModel.removeKeywordFocusGroup(keyword)
            }
            .show()
    }

    private fun showKeywordTimeSettings(keyword: String) {
        val config = viewModel.keywordBlockerConfig.value
        val timeLimit = config.keywordTimeLimits[keyword] ?: 0
        val reminderInterval = config.keywordReminderIntervals[keyword] ?: 5
        val currentUsage = viewModel.getKeywordUsageMinutes(keyword)

        val dialogView = layoutInflater.inflate(R.layout.dialog_keyword_time_settings, null)
        val etTimeLimit = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_time_limit)
        val etReminderInterval = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_reminder_interval)
        val tvCurrentUsage = dialogView.findViewById<TextView>(R.id.tv_current_usage)

        etTimeLimit.setText(if (timeLimit > 0) timeLimit.toString() else "")
        etReminderInterval.setText(reminderInterval.toString())
        tvCurrentUsage.text = getString(R.string.current_usage_today) + ": ${currentUsage.toLong()} min"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(keyword)
            .setView(dialogView)
            .setPositiveButton(R.string.save) { _, _ ->
                val newTimeLimit = etTimeLimit.text.toString().toIntOrNull() ?: 0
                var newReminderInterval = etReminderInterval.text.toString().toIntOrNull() ?: 5

                if (newTimeLimit in (1..4)) {
                    newReminderInterval = 0
                }

                if (newTimeLimit != timeLimit) {
                    viewModel.setKeywordTimeLimit(keyword, newTimeLimit)
                }
                if (newReminderInterval != reminderInterval) {
                    viewModel.setKeywordReminderInterval(keyword, newReminderInterval)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .setNeutralButton(R.string.clear_usage) { _, _ ->
                viewModel.clearKeywordUsage(keyword)
            }
            .show()
    }

    private fun showRemoveConfirmation(keyword: String) {
        var countdownTimer: CountDownTimer? = null

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_entry)
            .setMessage(getString(R.string.remove_entry_confirmation, keyword))
            .setPositiveButton(R.string.yes, null)
            .setNegativeButton(R.string.no, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.text = getString(R.string.yes_in_seconds, 20)
            positiveButton.setOnClickListener {
                viewModel.removeKeyword(keyword)
                dialog.dismiss()
            }

            countdownTimer = object : CountDownTimer(20_000L, 1_000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsRemaining = (millisUntilFinished / 1_000L).toInt()
                    positiveButton.text = getString(R.string.yes_in_seconds, secondsRemaining)
                }

                override fun onFinish() {
                    positiveButton.isEnabled = true
                    positiveButton.text = getString(R.string.yes)
                }
            }.start()
        }

        dialog.setOnDismissListener {
            countdownTimer?.cancel()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"
    }
}
