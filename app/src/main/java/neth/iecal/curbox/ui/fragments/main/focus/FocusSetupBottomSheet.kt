package neth.iecal.curbox.ui.fragments.main.focus

import android.app.Activity
import android.app.Dialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.databinding.DialogFocusSessionConfigBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class FocusSetupBottomSheet : BottomSheetDialogFragment() {

    private var _binding: DialogFocusSessionConfigBinding? = null
    private val binding get() = _binding!!
    private val viewModel: FocusViewModel by activityViewModels()

    private lateinit var autoCompleteAdapter: ArrayAdapter<ManualFocusGroup>

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val selectedApps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
            viewModel.newGroupSelectedApps = HashSet(selectedApps)
            binding.selectedAppCount.text = "Selected: " + selectedApps.size
            updateBlockModeVisibility()
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogFocusSessionConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupGroupSelectionDropdown()
        observeViewModel()
        binding.btnCreateGroup.setOnClickListener {
            // clear if creating new
            viewModel.selectedGroup = null
            binding.groupName.setText("")
            viewModel.newGroupSelectedApps = HashSet()
            binding.selectedAppCount.text = "Selected: 0"
            binding.exitable.isChecked = true
            binding.autoTurnOnDnd.isChecked = false
            binding.recurringToggle.isChecked = false
            binding.recurringSettings.visibility = View.GONE
            binding.recurringIntervalsContainer.removeAllViews()
            updateBlockModeVisibility()
            
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
        }

        binding.btnConfirmStart.setOnClickListener {
            viewModel.startFocusing()
            dismiss()
        }

        binding.groupDropdown.setOnItemClickListener { parent, view, position, id ->
            val clickedItem = parent.getItemAtPosition(position)
            val selectedGroup = clickedItem as ManualFocusGroup
            viewModel.selectedGroup = selectedGroup
            binding.btnEditGroup.visibility = View.VISIBLE
            binding.btnDeleteGroup.visibility = View.VISIBLE
        }

        binding.btnEditGroup.setOnClickListener {
            val group = viewModel.selectedGroup ?: return@setOnClickListener
            // Pre-fill the create group form with this group's details
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
            
            binding.groupName.setText(group.groupName)
            viewModel.newGroupSelectedApps = HashSet(group.packages)
            binding.selectedAppCount.text = "Selected: ${group.packages.size}"
            updateBlockModeVisibility()
            if (group.blockMode == FocusBlockMode.BLOCK_SELECTED) {
                binding.selectedBlockAction.check(R.id.btn_selected)
            } else {
                binding.selectedBlockAction.check(R.id.btn_block_all_excpt_selected)
            }
            binding.exitable.isChecked = group.exitable
            binding.autoTurnOnDnd.isChecked = group.autoTurnOnDnd
            
            // Pre-fill recurring settings
            binding.recurringToggle.isChecked = group.isRecurring
            binding.recurringSettings.visibility = if (group.isRecurring) View.VISIBLE else View.GONE
            binding.recurringIntervalsContainer.removeAllViews()
            if (group.isRecurring && group.dailyIntervals.isNotEmpty()) {
                val firstDayIntervals = group.dailyIntervals.values.firstOrNull() ?: emptyList()
                val dayKeys = group.dailyIntervals.keys.toList()
                binding.everydayToggle.isChecked = dayKeys.size >= 7
                for (interval in firstDayIntervals) {
                    addRecurringIntervalRow(interval.startHour, interval.startMinute, interval.endHour, interval.endMinute)
                }
            }
            // Note: we can either save as new or overwrite
            // To overwrite, we delete the old group before saving
        }

        binding.btnDeleteGroup.setOnClickListener {
            val group = viewModel.selectedGroup ?: return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete Focus Group")
                .setMessage("Are you sure you want to delete this group? All associated focus statistics will also be deleted.")
                .setPositiveButton("Delete") { _, _ ->
                    viewModel.removeGroup(group)
                    viewModel.selectedGroup = null
                    binding.groupDropdown.setText("", false)
                    binding.btnEditGroup.visibility = View.GONE
                    binding.btnDeleteGroup.visibility = View.GONE
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // Recurring toggle
        binding.recurringToggle.setOnCheckedChangeListener { _, isChecked ->
            binding.recurringSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnAddRecurringInterval.setOnClickListener {
            addRecurringIntervalRow()
        }

        // code dealing with new group creation
        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            selectAppsLauncher.launch(intent)
        }
                binding.autoTurnOnDnd.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val nm = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) {
                    binding.autoTurnOnDnd.isChecked = false
                    val intent = Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                    android.widget.Toast.makeText(requireContext(), "Please grant Do Not Disturb access to use this feature", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        binding.saveGroup.setOnClickListener {
            val isEditing = viewModel.selectedGroup != null
            val name = binding.groupName.text.toString().trim()
            if (name.isEmpty()) {
                binding.groupName.error = "Please enter a group name"
                return@setOnClickListener
            }

            val dailyIntervals = if (binding.recurringToggle.isChecked) {
                collectDailyIntervals()
            } else {
                mutableMapOf()
            }
            val newGroup = ManualFocusGroup(
                groupId = if (isEditing) viewModel.selectedGroup!!.groupId else java.util.UUID.randomUUID().toString(),
                groupName = name,
                packages = viewModel.newGroupSelectedApps,
                blockMode = if (viewModel.newGroupSelectedApps.isNotEmpty() && binding.selectedBlockAction.checkedButtonId == R.id.btn_block_all_excpt_selected)
                    FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED else FocusBlockMode.BLOCK_SELECTED,
                exitable = binding.exitable.isChecked,
                autoTurnOnDnd = binding.autoTurnOnDnd.isChecked,
                isRecurring = binding.recurringToggle.isChecked,
                dailyIntervals = dailyIntervals
            )
            
            if (isEditing) {
                viewModel.updateGroup(newGroup)
            } else {
                viewModel.addGroup(newGroup)
            }
            
            binding.createGroup.visibility = View.GONE
            binding.selectGrouo.visibility = View.VISIBLE
            
            // Re-select it if it was edited
            if (isEditing) {
                viewModel.selectedGroup = newGroup
                binding.groupDropdown.setText(newGroup.toString(), false)
            }
        }
        binding.selectedBlockAction.checkedButtonId
    }


    private fun updateBlockModeVisibility() {
        val hasApps = viewModel.newGroupSelectedApps.isNotEmpty()
        binding.selectedBlockAction.visibility = if (hasApps) View.VISIBLE else View.GONE
        binding.textView32.visibility = if (hasApps) View.VISIBLE else View.GONE
    }

    private fun addRecurringIntervalRow(startHour: Int = 9, startMinute: Int = 0, endHour: Int = 17, endMinute: Int = 0) {
        var startHour = startHour
        var startMinute = startMinute
        var endHour = endHour
        var endMinute = endMinute
        val row = layoutInflater.inflate(R.layout.item_time_range_interval, binding.recurringIntervalsContainer, false)
        val rowBinding = neth.iecal.curbox.databinding.ItemTimeRangeIntervalBinding.bind(row)

        fun updateTexts() {
            rowBinding.llStartTime.text = String.format("%02d:%02d", startHour, startMinute)
            rowBinding.llEndTime.text = String.format("%02d:%02d", endHour, endMinute)
        }

        updateTexts()

        rowBinding.llStartTime.setOnClickListener {
            showTimePicker { hour, minute ->
                startHour = hour
                startMinute = minute
                updateTexts()
            }
        }

        rowBinding.llEndTime.setOnClickListener {
            showTimePicker { hour, minute ->
                endHour = hour
                endMinute = minute
                updateTexts()
            }
        }

        rowBinding.btnRemove.setOnClickListener {
            binding.recurringIntervalsContainer.removeView(row)
        }

        binding.recurringIntervalsContainer.addView(row)
    }

    private fun showTimePicker(onTimeSelected: (Int, Int) -> Unit) {
        val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
            .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
            .setHour(12)
            .setMinute(0)
            .setTitleText("Select Time")
            .build()
        picker.addOnPositiveButtonClickListener {
            onTimeSelected(picker.hour, picker.minute)
        }
        picker.show(parentFragmentManager, "time_picker")
    }

    private fun collectDailyIntervals(): MutableMap<Int, MutableList<TimeInterval>> {
        val result = mutableMapOf<Int, MutableList<TimeInterval>>()
        val intervals = mutableListOf<TimeInterval>()

        for (i in 0 until binding.recurringIntervalsContainer.childCount) {
            val row = binding.recurringIntervalsContainer.getChildAt(i)
            val rowBinding = neth.iecal.curbox.databinding.ItemTimeRangeIntervalBinding.bind(row)
            val startParts = rowBinding.llStartTime.text.toString().split(":")
            val endParts = rowBinding.llEndTime.text.toString().split(":")
            if (startParts.size == 2 && endParts.size == 2) {
                intervals.add(TimeInterval(
                    startHour = startParts[0].toIntOrNull() ?: 9,
                    startMinute = startParts[1].toIntOrNull() ?: 0,
                    endHour = endParts[0].toIntOrNull() ?: 17,
                    endMinute = endParts[1].toIntOrNull() ?: 0
                ))
            }
        }

        if (intervals.isNotEmpty()) {
            if (binding.everydayToggle.isChecked) {
                for (day in 0..6) {
                    result[day] = intervals.toMutableList()
                }
            } else {
                result[0] = intervals.toMutableList()
            }
        }

        return result
    }

    private fun setupGroupSelectionDropdown() {
        autoCompleteAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            mutableListOf()
        )

        binding.groupDropdown.setAdapter(autoCompleteAdapter)
    }

    private fun observeViewModel() {
        // Collect the StateFlow safely, respecting the Fragment's lifecycle
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {

                viewModel.groups.collect { suggestions ->
                    // Update the adapter data
                    autoCompleteAdapter.clear()
                    autoCompleteAdapter.addAll(suggestions)
                    autoCompleteAdapter.notifyDataSetChanged()
                }

            }
        }
    }
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "focus_session_screen_config"
    }
}