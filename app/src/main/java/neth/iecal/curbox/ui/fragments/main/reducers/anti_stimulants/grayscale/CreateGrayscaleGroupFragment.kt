package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale

import android.app.Activity
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
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.databinding.FragmentCreateGrayscaleGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import android.content.Intent
import java.util.UUID

class CreateGrayscaleGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_grayscale_group"
    }

    private var _binding: FragmentCreateGrayscaleGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: List<String> = emptyList()
    private var isPrefilled = false
    private val viewModel: GrayscaleViewModel by activityViewModels()

    private var initialGroupName: String = ""
    private var initialSelectedApps: List<String> = emptyList()
    private var initialTimeConfig: String = ""

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateGrayscaleGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")

        if (groupId == null) {
            viewModel.currentTimeConfig = AppTimeConfig(
                everydayIntervals = mutableListOf(
                    TimeInterval(startHour = 0, endHour = 7)
                )
            )
            captureInitialState()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.groupId == groupId }
                    if (group != null && !isPrefilled) {
                        isPrefilled = true
                        binding.textView.text = "Edit Grayscale Group"
                        binding.etGroupName.setText(group.groupName)
                        selectedApps = group.packages.toList()
                        binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"

                        viewModel.currentTimeConfig = group.timeConfig.copy()
                        
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

        binding.btnConfigureSchedule.setOnClickListener {
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment_type", "app_time_config")
                putExtra("mode", "GRAYSCALE")
            }
            startActivity(intent)
        }

        binding.btnDeleteGroup.visibility = if (groupId != null) View.VISIBLE else View.GONE
        binding.btnDeleteGroup.setOnClickListener {
            if (groupId != null) {
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
        }

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
        val currentTimeConfig = Gson().toJson(viewModel.currentTimeConfig)

        return currentGroupName != initialGroupName ||
                currentSelectedApps != initialSelectedApps ||
                currentTimeConfig != initialTimeConfig
    }

    private fun captureInitialState() {
        initialGroupName = binding.etGroupName.text.toString().trim()
        initialSelectedApps = selectedApps.toList()
        initialTimeConfig = Gson().toJson(viewModel.currentTimeConfig)
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
        val targetExistingGroup = viewModel.groups.value.find { it.groupId == savedGroupId }

        val newGroup = GrayscaleGroup(
            groupId = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.groupId else UUID.randomUUID().toString(),
            groupName = name,
            packages = HashSet(selectedApps),
            timeConfig = viewModel.currentTimeConfig
        )

        if (isEditingRecord && targetExistingGroup != null) {
            viewModel.updateGroup(newGroup)
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
