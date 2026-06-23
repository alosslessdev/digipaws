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
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.FocusBlockMode
import neth.iecal.curbox.data.models.ManualFocusGroup
import neth.iecal.curbox.data.models.TimeInterval
import neth.iecal.curbox.databinding.DialogFocusSessionConfigBinding
import neth.iecal.curbox.hardcoded.URL_BAR_ID_LIST
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

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

        if (viewModel.groups.value.isEmpty()) {
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
        } else {
            viewModel.selectedGroup?.let { group ->
                binding.groupDropdown.setText(group.toString(), false)
                binding.btnEditGroup.visibility = View.VISIBLE
                binding.btnDeleteGroup.visibility = View.VISIBLE
            }
        }

        binding.btnCreateGroup.setOnClickListener {
            viewModel.selectedGroup = null
            binding.groupName.setText("")
            viewModel.newGroupSelectedApps = HashSet()
            viewModel.newGroupSelectedKeywords = hashSetOf()
            binding.selectedAppCount.text = "Selected: 0"
            binding.selectedWebsiteCount.text = "Selected: ${viewModel.newGroupSelectedKeywords.size}"
            binding.exitable.isChecked = true
            binding.autoTurnOnDnd.isChecked = false
            binding.recurringToggle.isChecked = false
            binding.recurringSettings.visibility = View.GONE
            binding.recurringIntervalsContainer.removeAllViews()
            
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
            binding.createGroup.visibility = View.VISIBLE
            binding.selectGrouo.visibility = View.GONE
            
            binding.groupName.setText(group.groupName)
            viewModel.newGroupSelectedApps = HashSet(group.packages)
            viewModel.newGroupSelectedKeywords = HashSet(group.keywords)
            binding.selectedAppCount.text = "Selected: ${group.packages.size}"
            binding.selectedWebsiteCount.text = "Selected: ${group.keywords.size}"
            if (group.blockMode == FocusBlockMode.BLOCK_SELECTED) {
                binding.selectedBlockAction.check(R.id.btn_selected)
            } else {
                binding.selectedBlockAction.check(R.id.btn_block_all_excpt_selected)
            }
            binding.exitable.isChecked = group.exitable
            binding.autoTurnOnDnd.isChecked = group.autoTurnOnDnd
            
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

        binding.recurringToggle.setOnCheckedChangeListener { _, isChecked ->
            binding.recurringSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        binding.btnAddRecurringInterval.setOnClickListener {
            addRecurringIntervalRow()
        }

        binding.btnSelectApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(viewModel.newGroupSelectedApps))
            selectAppsLauncher.launch(intent)
        }

        binding.btnAddWebsites.setOnClickListener {
            showAddWebsitesDialog()
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
            val blockMode = if(binding.selectedBlockAction.checkedButtonId == R.id.btn_selected) FocusBlockMode.BLOCK_SELECTED else FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED
            
            if (viewModel.newGroupSelectedKeywords.isNotEmpty()) {
                val supportedBrowsers = URL_BAR_ID_LIST.keys
                val hasBrowserSelected = viewModel.newGroupSelectedApps.any { it in supportedBrowsers }
                
                if (!hasBrowserSelected) {
                    if (blockMode == FocusBlockMode.BLOCK_ALL_EXCEPT_SELECTED) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("No Browser Selected")
                            .setMessage("You have set some websites to be allowed, but no supported browser is in your 'Allowed Apps' list. \n\nTo access these websites, please add a browser (like Chrome or Firefox) to the selected apps.")
                            .setPositiveButton("Add Browser") { _, _ ->
                                binding.btnSelectApps.performClick()
                            }
                            .setNegativeButton("Save Anyway") { _, _ ->
                                saveFocusGroup(isEditing, blockMode)
                            }
                            .show()
                    } else {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle("Website Blocking Notice")
                            .setMessage("You have set some websites to be blocked, but no browser is in your 'Blocked Apps' list. \n\nNote that website blocking only works on supported browsers (like Chrome or Firefox). Any other browser will still be able to access these websites. Consider blocking unsupported browsers if needed.")
                            .setPositiveButton("Add Browser") { _, _ ->
                                binding.btnSelectApps.performClick()
                            }
                            .setNegativeButton("Save Anyway") { _, _ ->
                                saveFocusGroup(isEditing, blockMode)
                            }
                            .show()
                    }
                    return@setOnClickListener
                }
            }

            saveFocusGroup(isEditing, blockMode)
        }
    }

    private fun saveFocusGroup(isEditing: Boolean, blockMode: FocusBlockMode) {
        val dailyIntervals = if (binding.recurringToggle.isChecked) {
            collectDailyIntervals()
        } else {
            mutableMapOf()
        }
        val newGroup = ManualFocusGroup(
            groupId = if (isEditing) viewModel.selectedGroup!!.groupId else UUID.randomUUID().toString(),
            groupName = binding.groupName.text.toString(),
            packages = viewModel.newGroupSelectedApps,
            keywords = viewModel.newGroupSelectedKeywords,
            blockMode = blockMode,
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

        viewModel.selectedGroup = newGroup
        binding.groupDropdown.setText(newGroup.toString(), false)
        binding.btnEditGroup.visibility = View.VISIBLE
        binding.btnDeleteGroup.visibility = View.VISIBLE
    }

    private fun showAddWebsitesDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_focus_add_websites, null)
        val etWebsite = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_website)
        val btnAdd = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_add)
        val rvWebsites = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_websites)
        
        val tempKeywords = ArrayList(viewModel.newGroupSelectedKeywords)
        val adapter = KeywordAdapter(tempKeywords)
        rvWebsites.adapter = adapter

        btnAdd.setOnClickListener {
            val text = etWebsite.text.toString().trim()
            if (text.isNotEmpty()) {
                val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                parts.forEach {
                    if (!tempKeywords.contains(it)) {
                        tempKeywords.add(it)
                    }
                }
                etWebsite.setText("")
                adapter.notifyDataSetChanged()
            }
        }

        etWebsite.setOnEditorActionListener { _, _, _ ->
            btnAdd.performClick()
            true
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Websites/Keywords")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                viewModel.newGroupSelectedKeywords = HashSet(tempKeywords)
                binding.selectedWebsiteCount.text = "Selected: ${tempKeywords.size}"
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    inner class KeywordAdapter(private val items: MutableList<String>) : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: android.widget.TextView = view.findViewById(R.id.tv_keyword)
            val btnRemove: android.widget.ImageButton = view.findViewById(R.id.btn_remove)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_keyword, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val keyword = items[position]
            holder.tvKeyword.text = keyword
            holder.btnRemove.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos != RecyclerView.NO_POSITION) {
                    items.removeAt(currentPos)
                    notifyItemRemoved(currentPos)
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun addRecurringIntervalRow(startHour: Int = 9, startMinute: Int = 0, endHour: Int = 17, endMinute: Int = 0) {
        var sh = startHour
        var sm = startMinute
        var eh = endHour
        var em = endMinute
        val row = layoutInflater.inflate(R.layout.item_time_range_interval, binding.recurringIntervalsContainer, false)
        val rowBinding = neth.iecal.curbox.databinding.ItemTimeRangeIntervalBinding.bind(row)

        fun updateTexts() {
            rowBinding.llStartTime.text = String.format("%02d:%02d", sh, sm)
            rowBinding.llEndTime.text = String.format("%02d:%02d", eh, em)
        }

        updateTexts()

        rowBinding.llStartTime.setOnClickListener {
            showTimePicker { hour, minute ->
                sh = hour
                sm = minute
                updateTexts()
            }
        }

        rowBinding.llEndTime.setOnClickListener {
            showTimePicker { hour, minute ->
                eh = hour
                em = minute
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
                    result[day] = ArrayList(intervals)
                }
            } else {
                result[0] = ArrayList(intervals)
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
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.groups.collect { suggestions ->
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
