package neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale

import neth.iecal.curbox.R

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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.data.models.GrayscaleGroup
import neth.iecal.curbox.databinding.FragmentCreateGrayscaleGroupBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

class CreateGrayscaleGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_grayscale_group"
    }

    private var _binding: FragmentCreateGrayscaleGroupBinding? = null
    private val binding get() = _binding!!

    private var selectedApps: ArrayList<String> = arrayListOf()
    private var isPrefilled = false
    private val viewModel: GrayscaleViewModel by activityViewModels()

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

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateGrayscaleGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }

        var isEditing = false
        var existingGroup: GrayscaleGroup? = null
        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val prefillPackage = requireActivity().intent.getStringExtra("prefill_package")

        if (groupId == null && !isPrefilled && prefillPackage != null) {
            isPrefilled = true
            selectedApps = arrayListOf(prefillPackage)
            binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"
        }

        if (groupId == null) {
            viewModel.currentDailyIntervals = mutableMapOf()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.groupId == groupId }
                    if (group != null && !isEditing) {
                        isEditing = true
                        existingGroup = group
                        binding.toolbar.title = "Edit Grayscale Group"
                        binding.etGroupName.setText(group.groupName)
                        selectedApps = ArrayList(group.packages.toList())
                        binding.btnSelectApps.text = "Select Apps (${selectedApps.size})"

                        viewModel.currentDailyIntervals = group.dailyIntervals.toMutableMap()

                        binding.toolbar.menu.clear()
                        val deleteItem = binding.toolbar.menu.add(0, 1001, 0, "Delete")
                        deleteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                        binding.toolbar.setOnMenuItemClickListener { item ->
                            if (item.itemId == 1001) {
                                viewModel.removeGroup(group)
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

        binding.btnConfigureSchedule.setOnClickListener {
            GrayscaleTimeSettingsFragment().show(parentFragmentManager, GrayscaleTimeSettingsFragment.FRAGMENT_ID)
        }

        binding.fabSaveGroup.setOnClickListener {
            val name = binding.etGroupName.text.toString().trim()
            if (name.isEmpty()) {
                binding.etGroupName.error = "Please enter a group name"
                return@setOnClickListener
            }

            val savedGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
            val isEditingRecord = savedGroupId != null
            val targetExistingGroup = viewModel.groups.value.find { it.groupId == savedGroupId }

            val newGroup = GrayscaleGroup(
                groupId = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.groupId else UUID.randomUUID().toString(),
                groupName = name,
                packages = HashSet(selectedApps),
                dailyIntervals = viewModel.currentDailyIntervals
            )

            if (isEditingRecord && targetExistingGroup != null) {
                viewModel.updateGroup(newGroup)
            } else {
                viewModel.addGroup(newGroup)
            }

            Toast.makeText(requireContext(), getString(R.string.group_saved_successfully), Toast.LENGTH_SHORT).show()
            requireActivity().finish()
        }
    }
    
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
