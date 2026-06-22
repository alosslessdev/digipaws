package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
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
import java.util.UUID
import kotlin.jvm.java

class CreateAppGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_app_group"
    }

    private var _binding: FragmentCreateAppGroupBinding? = null
    // This property is only valid between onCreateView and onDestroyView.
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

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

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
                        binding.toolbar.title = "Edit App Group"
                        binding.etGroupName.setText(group.name)
                        selectedApps = ArrayList(group.selectedPackages)
                        binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"

                        if (group.blockingType == AppBlockingType.Usage) {
                            binding.rgBlockingType.check(R.id.rb_usage_based)
                            viewModel.currentUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                        } else {
                            binding.rgBlockingType.check(R.id.rb_time_based)
                            viewModel.currentTimeConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                        }
                        viewModel.warningScrnConfig = group.warningScreenConfig

                        binding.toolbar.menu.clear()
                        val deleteItem = binding.toolbar.menu.add(0, 1001, 0, "Delete")
                        deleteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                        binding.toolbar.setOnMenuItemClickListener { item ->
                            if (item.itemId == 1001) {
                                isDeleting = true
                                viewModel.deleteGroup(group.id)
                                Toast.makeText(requireContext(), getString(R.string.group_deleted), Toast.LENGTH_SHORT).show()
                                requireActivity().finish()
                                true
                            } else {
                                false
                            }
                        }
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
            val isUsageBased = binding.rgBlockingType.checkedRadioButtonId == R.id.rb_usage_based

            if (isUsageBased) {
                UsageBasedSettingsFragment().show(parentFragmentManager, UsageBasedSettingsFragment.FRAGMENT_ID)
            } else {
                TimeBasedSettingsFragment().show(parentFragmentManager, UsageBasedSettingsFragment.FRAGMENT_ID)
            }
        }
        binding.configureWarningScreen.setOnClickListener {
            AppBlockerWarningConfigFragment().show(parentFragmentManager,
                AppBlockerWarningConfigFragment.FRAGMENT_ID)
        }

        binding.fabSaveGroup.setOnClickListener {
            saveGroup()
        }
    }

    private fun saveGroup() {
        if (_binding == null || isDeleting) return
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = "Please enter a group name"
            return
        }

        val isUsageBased = binding.rgBlockingType.checkedRadioButtonId == R.id.rb_usage_based
        val blockingType = if (isUsageBased) AppBlockingType.Usage else AppBlockingType.Timed

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
            } else {
                Gson().toJson(viewModel.currentTimeConfig)
            },
            warningScreenConfig = viewModel.warningScrnConfig
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
