package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AutoDndGroup
import neth.iecal.curbox.databinding.FragmentCreateAutodndGroupBinding
import java.util.UUID

class CreateAutoDndGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_autodnd_group"
    }

    private var _binding: FragmentCreateAutodndGroupBinding? = null
    private val binding get() = _binding!!

    private var isPrefilled = false
    private val viewModel: AutoDndViewModel by activityViewModels()

    private var initialGroupName: String = ""
    private var initialTimeConfig: String = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateAutodndGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")

        if (groupId == null) {
            viewModel.currentTimeConfig = AppTimeConfig()
            captureInitialState()
        }

        if (groupId != null) {
            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.groups.collectLatest { groups ->
                    val group = groups.find { it.groupId == groupId }
                    if (group != null && !isPrefilled) {
                        isPrefilled = true
                        binding.textView.text = "Edit Auto DND Group"
                        binding.etGroupName.setText(group.groupName)

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

        binding.btnConfigureSchedule.setOnClickListener {
            val intent = Intent(requireContext(), neth.iecal.curbox.ui.activity.FragmentActivity::class.java).apply {
                putExtra("fragment_type", "app_time_config")
                putExtra("mode", "AUTODND")
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
        val currentTimeConfig = Gson().toJson(viewModel.currentTimeConfig)

        return currentGroupName != initialGroupName ||
                currentTimeConfig != initialTimeConfig
    }

    private fun captureInitialState() {
        initialGroupName = binding.etGroupName.text.toString().trim()
        initialTimeConfig = Gson().toJson(viewModel.currentTimeConfig)
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = "Please enter a group name"
            return
        }
        
        // Check for DND access before saving
        val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) {
            val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
            Toast.makeText(requireContext(), "Please grant Do Not Disturb access to use this feature", Toast.LENGTH_LONG).show()
            return
        }

        val savedGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        val isEditingRecord = savedGroupId != null
        val targetExistingGroup = viewModel.groups.value.find { it.groupId == savedGroupId }

        val newGroup = AutoDndGroup(
            groupId = if (isEditingRecord && targetExistingGroup != null) targetExistingGroup.groupId else UUID.randomUUID().toString(),
            groupName = name,
            autoTurnOnDnd = true,
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
