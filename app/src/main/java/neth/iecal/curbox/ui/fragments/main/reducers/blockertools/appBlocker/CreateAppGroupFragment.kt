package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.databinding.FragmentCreateAppGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.utils.ViewUtils
import java.util.UUID

class CreateAppGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_app_group"
    }

    private var _binding: FragmentCreateAppGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: ArrayList<String> = arrayListOf()
    private var isPrefilled = false
    private val viewModel: AppBlockerSettingViewModel by activityViewModels()

    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                selectedApps = apps
                binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"
            }
        }
    }



    private var isDeleting = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAppGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBlockingTypeSelection()

        var isEditing = false
        var existingGroup: AppGroup? = null
        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val prefillPackage = requireActivity().intent.getStringExtra("prefill_package")

        if (groupId == null && !isPrefilled && prefillPackage != null) {
            isPrefilled = true
            selectedApps = arrayListOf(prefillPackage)
            binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.id == groupId }
                    if (group != null && !isEditing) {
                        isEditing = true
                        existingGroup = group
                        binding.textView2.text = "Edit App Group"
                        binding.etGroupName.setText(group.name)
                        selectedApps = ArrayList(group.selectedPackages)
                        binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"

                        binding.btnDeleteGroup.visibility = View.VISIBLE
                        binding.btnDeleteGroup.setOnClickListener {
                            isDeleting = true
                            viewModel.deleteGroup(group.id)
                            Toast.makeText(requireContext(), getString(R.string.group_deleted), Toast.LENGTH_SHORT).show()
                            requireActivity().finish()
                        }

                        if (group.blockingType == AppBlockingType.Usage) {
                            binding.rbUsageBased.isChecked = true
                            binding.rbTimeBased.isChecked = false
                            binding.rbOnOpen.isChecked = false
                            viewModel.currentUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                        } else if (group.blockingType == AppBlockingType.Timed) {
                            binding.rbTimeBased.isChecked = true
                            binding.rbUsageBased.isChecked = false
                            binding.rbOnOpen.isChecked = false
                            viewModel.currentTimeConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                        } else {
                            binding.rbOnOpen.isChecked = true
                            binding.rbUsageBased.isChecked = false
                            binding.rbTimeBased.isChecked = false
                            binding.btnConfigureSettings.visibility = View.GONE
                        }
                        viewModel.warningScrnConfig = group.warningScreenConfig
                    }
                }
            }
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", selectedApps)
            selectAppsLauncher.launch(intent)
        }

        binding.btnConfigureSettings.setOnClickListener {
            val isUsageBased = binding.rbUsageBased.isChecked

            if (isUsageBased) {
                UsageBasedSettingsFragment().show(parentFragmentManager, UsageBasedSettingsFragment.FRAGMENT_ID)
            } else {
                TimeBasedSettingsFragment().show(parentFragmentManager, UsageBasedSettingsFragment.FRAGMENT_ID)
            }
        }
        binding.configureWarningScreen.setOnClickListener {
            val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = groupId == null,
                isOnOpen = binding.rbOnOpen.isChecked
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            val configStr = bundle.getString("result_config")
            if (configStr != null) {
                viewModel.warningScrnConfig = com.google.gson.Gson().fromJson(configStr, neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig::class.java)
            }
        }

        binding.fabSaveGroup.setOnClickListener {
            saveGroup()
        }
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

        binding.btnHelpUsage.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Set a daily time limit for these apps. Once reached, they will be blocked for the rest of the day.", "https://curbox.app/docs/reducers/app-pause/")
        }

        binding.btnHelpTime.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Allow these apps only during specific time intervals during the day (e.g., during work hours).", "https://curbox.app/docs/reducers/app-pause/")
        }

        binding.btnHelpOnOpen.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Show a warning screen every time you open these apps. Access is only allowed for the current session.", "https://curbox.app/docs/reducers/app-pause/")
        }
    }

    private fun saveGroup() {
        if (_binding == null || isDeleting) return
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = "Please enter a group name"
            return
        }

        if (selectedApps.isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.please_select_at_least_one_app), Toast.LENGTH_SHORT).show()
            return
        }

        val isUsageBased = binding.rbUsageBased.isChecked
        val isOnOpen = binding.rbOnOpen.isChecked
        val blockingType = when {
            isUsageBased -> AppBlockingType.Usage
            isOnOpen -> AppBlockingType.OnOpen
            else -> AppBlockingType.Timed
        }

        val savedGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val isEditingRecord = savedGroupId != null
        val targetExistingGroup = viewModel.groups.value.find { it.id == savedGroupId }

        val newGroupId = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.id else UUID.randomUUID().toString()

        val newGroup = AppGroup(
            id = newGroupId,
            name = name,
            selectedPackages = selectedApps.toList(),
            blockingType = blockingType,
            isActive = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.isActive else true,
            setting = if(isUsageBased) {
                Gson().toJson(viewModel.currentUsageConfig)
            } else if (isOnOpen) {
                ""
            } else {
                Gson().toJson(viewModel.currentTimeConfig)
            },
            warningScreenConfig = viewModel.warningScrnConfig.copy(isOnOpenConfig = isOnOpen)
        )

        if (isEditingRecord && targetExistingGroup != null) {
            viewModel.updateGroupById(newGroup)
        } else {
            viewModel.addGroup(newGroup)
            if (arguments == null) {
                arguments = Bundle()
            }
            arguments?.putString("group_id", newGroupId)
        }

        Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
        requireActivity().finish()
    }
}
