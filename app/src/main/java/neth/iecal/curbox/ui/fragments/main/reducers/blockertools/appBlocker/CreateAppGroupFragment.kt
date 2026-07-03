package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.databinding.FragmentCreateAppGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

class CreateAppGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_app_group"
    }

    private var _binding: FragmentCreateAppGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: List<String> = emptyList()
    private var isPrefilled = false
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    private var initialGroupName: String = ""
    private var initialSelectedApps: List<String> = emptyList()
    private var initialBlockingType: AppBlockingType = AppBlockingType.Usage
    private var initialSetting: String = ""
    private var initialWarningConfig: String = ""

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"
            }
        }
    }

    private val configureSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val json = result.data?.getStringExtra(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment.EXTRA_CONFIG_JSON)
            val type = result.data?.getStringExtra(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment.EXTRA_CONFIG_TYPE)
            if (json != null) {
                if (type == "time") {
                    viewModel.currentTimeConfig = Gson().fromJson(json, AppTimeConfig::class.java)
                } else if (type == "usage") {
                    viewModel.currentUsageConfig = Gson().fromJson(json, AppUsageConfig::class.java)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")

        if (groupId == null) {
            viewModel.currentUsageConfig = AppUsageConfig()
            viewModel.currentTimeConfig = AppTimeConfig()
            viewModel.warningScrnConfig = AppBlockerWarningScreenConfig()
            captureInitialState()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.id == groupId }
                    if (group != null && !isPrefilled) {
                        isPrefilled = true
                        binding.textView2.text = "Edit App Group"
                        binding.etGroupName.setText(group.name)
                        selectedApps = group.selectedPackages.toList()
                        binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"

                        when (group.blockingType) {
                            AppBlockingType.Usage -> {
                                binding.rbUsageBased.isChecked = true
                                binding.rbTimeBased.isChecked = false
                                binding.rbOnOpen.isChecked = false
                                viewModel.currentUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                            }
                            AppBlockingType.Timed -> {
                                binding.rbTimeBased.isChecked = true
                                binding.rbUsageBased.isChecked = false
                                binding.rbOnOpen.isChecked = false
                                viewModel.currentTimeConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                            }
                            AppBlockingType.OnOpen -> {
                                binding.rbOnOpen.isChecked = true
                                binding.rbUsageBased.isChecked = false
                                binding.rbTimeBased.isChecked = false
                            }
                        }

                        if (group.blockingType == AppBlockingType.OnOpen) {
                            binding.btnConfigureSettings.visibility = View.GONE
                        }
                        viewModel.warningScrnConfig = group.warningScreenConfig

                        binding.btnDeleteGroup.visibility = View.VISIBLE
                        binding.btnDeleteGroup.setOnClickListener {
                            MaterialAlertDialogBuilder(requireContext())
                                .setTitle(R.string.delete_group)
                                .setMessage("Are you sure you want to delete this group?")
                                .setPositiveButton(R.string.delete) { _, _ ->
                                    viewModel.deleteGroup(groupId)
                                    Toast.makeText(requireContext(), R.string.group_deleted, Toast.LENGTH_SHORT).show()
                                    requireActivity().finish()
                                }
                                .setNegativeButton(R.string.cancel, null)
                                .show()
                        }

                        captureInitialState()
                    }
                }
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedApps))
            selectAppsLauncher.launch(intent)
        }

        binding.btnConfigureSettings.setOnClickListener {
            val type = when {
                binding.rbUsageBased.isChecked -> AppBlockingType.Usage
                binding.rbOnOpen.isChecked -> AppBlockingType.OnOpen
                else -> AppBlockingType.Timed
            }
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                val isUsage = type == AppBlockingType.Usage
                putExtra("fragment_type", if (isUsage) "app_usage_config" else "app_time_config")
                putExtra(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.BaseTimeSettingsFragment.ARG_INITIAL_CONFIG, 
                    if (isUsage) Gson().toJson(viewModel.currentUsageConfig) else Gson().toJson(viewModel.currentTimeConfig))
                putExtra("mode", "APP_BLOCKER")
            }
            configureSettingsLauncher.launch(intent)
        }

        binding.configureWarningScreen.setOnClickListener {
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.FRAGMENT_ID)
                putExtra(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.ARG_CONFIG, com.google.gson.Gson().toJson(viewModel.warningScrnConfig))
                putExtra("mode", "APP_BLOCKER")
            }
            startActivity(intent)
        }

        setupBlockingTypeSelection()

        binding.fabSaveGroup.setOnClickListener {
            saveGroup()
        }

        setupBackPressHandling()
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
                saveGroup()
            }
            .setNegativeButton(R.string.btn_discard) { _, _ ->
                requireActivity().finish()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun hasChanges(): Boolean {
        val currentGroupName = binding.etGroupName.text.toString().trim()
        val currentSelectedApps = selectedApps.toList()
        val currentBlockingType = when {
            binding.rbUsageBased.isChecked -> AppBlockingType.Usage
            binding.rbOnOpen.isChecked -> AppBlockingType.OnOpen
            else -> AppBlockingType.Timed
        }
        val currentSetting = if (binding.rbUsageBased.isChecked) {
            Gson().toJson(viewModel.currentUsageConfig)
        } else if (binding.rbOnOpen.isChecked) {
            ""
        } else {
            Gson().toJson(viewModel.currentTimeConfig)
        }
        val currentWarningConfig = Gson().toJson(viewModel.warningScrnConfig)

        return currentGroupName != initialGroupName ||
                currentSelectedApps != initialSelectedApps ||
                currentBlockingType != initialBlockingType ||
                currentSetting != initialSetting ||
                currentWarningConfig != initialWarningConfig
    }

    private fun captureInitialState() {
        initialGroupName = binding.etGroupName.text.toString().trim()
        initialSelectedApps = selectedApps.toList()
        initialBlockingType = when {
            binding.rbUsageBased.isChecked -> AppBlockingType.Usage
            binding.rbOnOpen.isChecked -> AppBlockingType.OnOpen
            else -> AppBlockingType.Timed
        }
        initialSetting = if (binding.rbUsageBased.isChecked) {
            Gson().toJson(viewModel.currentUsageConfig)
        } else if (binding.rbOnOpen.isChecked) {
            ""
        } else {
            Gson().toJson(viewModel.currentTimeConfig)
        }
        initialWarningConfig = Gson().toJson(viewModel.warningScrnConfig)
    }

    private fun setupBlockingTypeSelection() {
        val radioButtons = listOf(binding.rbUsageBased, binding.rbTimeBased, binding.rbOnOpen)
        radioButtons.forEach { rb ->
            rb.setOnClickListener {
                radioButtons.forEach { it.isChecked = false }
                rb.isChecked = true
                binding.btnConfigureSettings.visibility = if (rb != binding.rbOnOpen) View.VISIBLE else View.GONE
            }
        }
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = "Please enter a group name"
            return
        }
        
        if (selectedApps.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one_app), Toast.LENGTH_SHORT).show()
            return
        }

        val savedGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val isEditingRecord = savedGroupId != null
        val targetExistingGroup = viewModel.groups.value.find { it.id == savedGroupId }

        val isUsageBased = binding.rbUsageBased.isChecked
        val isOnOpen = binding.rbOnOpen.isChecked
        val blockingType = when {
            isUsageBased -> AppBlockingType.Usage
            isOnOpen -> AppBlockingType.OnOpen
            else -> AppBlockingType.Timed
        }

        val setting = if (isUsageBased) {
            Gson().toJson(viewModel.currentUsageConfig)
        } else if (isOnOpen) {
            ""
        } else {
            Gson().toJson(viewModel.currentTimeConfig)
        }

        val newGroup = AppGroup(
            id = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.id else UUID.randomUUID().toString(),
            name = name,
            selectedPackages = selectedApps,
            blockingType = blockingType,
            isActive = targetExistingGroup?.isActive ?: true,
            setting = setting,
            warningScreenConfig = viewModel.warningScrnConfig
        )

        if (isEditingRecord && targetExistingGroup != null) {
            viewModel.updateGroupById(newGroup)
        } else {
            viewModel.addGroup(newGroup)
        }

        Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
        requireActivity().finish()
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
