package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
<<<<<<< HEAD
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
=======
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
<<<<<<< HEAD
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
=======
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.switchmaterial.SwitchMaterial
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.FragmentActivity

class KeywordBlockerFragment : Fragment() {

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (!viewModel.keywordBlockerConfig.value.isActive) {
            viewModel.setIsActive(true)
        }
        binding.rvKeywordGroups.layoutManager = LinearLayoutManager(requireContext())
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.btnMenu.setOnClickListener { view ->
            showPopupMenu(view)
        }

        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_blocker, popup.menu)

        val config = viewModel.keywordBlockerConfig.value
        popup.menu.findItem(R.id.menu_block_unsupported_browsers).isChecked = config.blockAllExceptSupported

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_block_unsupported_browsers -> {
                    val newValue = !item.isChecked
                    item.isChecked = newValue
                    viewModel.setBlockAllExceptSupported(newValue)
                    true
                }
                R.id.menu_help -> {
                    val url = "https://curbox.app/docs/reducers/keyword-blocker/"
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    true
                }
                else -> false
            }
        }
<<<<<<< HEAD

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
=======
        popup.show()
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                isUpdatingUi = true

                if (config.keywordGroups.isEmpty()) {
                    binding.tvEmptyState.visibility = View.VISIBLE
                    binding.rvKeywordGroups.visibility = View.GONE
                } else {
                    binding.tvEmptyState.visibility = View.GONE
                    binding.rvKeywordGroups.visibility = View.VISIBLE
                    binding.rvKeywordGroups.adapter = KeywordGroupAdapter(config.keywordGroups)
                }
<<<<<<< HEAD

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

                if (binding.switchTimeTracking.isChecked != config.isTimeTrackingEnabled) {
                    binding.switchTimeTracking.isChecked = config.isTimeTrackingEnabled
                    binding.layoutTimeTrackingSettings.visibility = if (config.isTimeTrackingEnabled) View.VISIBLE else View.GONE
                }

                val currentThreshold = binding.etClusteringThreshold.text.toString().toIntOrNull() ?: 5
                if (currentThreshold != config.clusteringThresholdMinutes) {
                    binding.etClusteringThreshold.setText(config.clusteringThresholdMinutes.toString())
                }

                updateKeywordsList(config.blockedKeywords)

=======
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
                isUpdatingUi = false
            }
        }
    }

<<<<<<< HEAD
    private fun updateKeywordsList(keywords: List<String>) {
        binding.cgKeywords.removeAllViews()
        for (keyword in keywords) {
            val chip = Chip(requireContext()).apply {
                text = keyword
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    showRemoveConfirmation(keyword)
                }
                setOnClickListener {
                    showKeywordTimeSettings(keyword)
                }
            }
            binding.cgKeywords.addView(chip)
=======
    inner class KeywordGroupAdapter(private val groupList: List<KeywordGroup>) :
        RecyclerView.Adapter<KeywordGroupAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
>>>>>>> 62c92183a67cb54ed11a3304ad8bc7018c175f26
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groupList[position]
            holder.tvName.text = group.name
            val typeText = if (group.blockingType == AppBlockingType.Usage) "Usage Based" else "Time Based"
            holder.tvDetails.text = "${group.selectedKeywords.size} Keywords • $typeText"
            
            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                viewModel.updateGroupActiveState(group.id, isChecked)
            }
            
            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
                    putExtra("group_id", group.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groupList.size
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

                if (newTimeLimit in 1..4) {
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
