package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.asFlow
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.databinding.FragmentCreateKeywordGroupBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import java.util.UUID

class CreateKeywordGroupFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "create_keyword_group"
    }

    private var _binding: FragmentCreateKeywordGroupBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var selectedKeywords = mutableListOf<String>()
    private val keywordAdapter by lazy { KeywordAdapter() }
    private var isEditing = false
    private var existingGroupId: String? = null
    private var isGroupActive = true

    private var initialGroupName: String = ""
    private var initialKeywords: List<String> = emptyList()
    private var initialBlockingType: AppBlockingType = AppBlockingType.Usage
    private var initialSetting: String = ""
    private var initialWarningConfig: String = ""

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importKeywordsFromFile(it) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCreateKeywordGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        existingGroupId = activity?.intent?.getStringExtra("result_id")
        
        if (existingGroupId != null) {
            loadExistingGroup(existingGroupId!!)
        } else {
            viewModel.currentUsageConfig = AppUsageConfig()
            viewModel.currentTimeConfig = AppTimeConfig()
            viewModel.warningScrnConfig = AppBlockerWarningScreenConfig()
            captureInitialState()
            updateKeywordsList()
        }

        setupListeners()
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
        val currentKeywords = selectedKeywords.toList()
        val currentBlockingType = if (binding.rbUsageBased.isChecked) AppBlockingType.Usage else AppBlockingType.Timed
        val currentSetting = if (currentBlockingType == AppBlockingType.Usage) Gson().toJson(viewModel.currentUsageConfig) else Gson().toJson(viewModel.currentTimeConfig)
        val currentWarningConfig = Gson().toJson(viewModel.warningScrnConfig)

        return currentGroupName != initialGroupName ||
                currentKeywords != initialKeywords ||
                currentBlockingType != initialBlockingType ||
                currentSetting != initialSetting ||
                currentWarningConfig != initialWarningConfig
    }

    private fun captureInitialState() {
        initialGroupName = binding.etGroupName.text.toString().trim()
        initialKeywords = selectedKeywords.toList()
        initialBlockingType = if (binding.rbUsageBased.isChecked) AppBlockingType.Usage else AppBlockingType.Timed
        initialSetting = if (initialBlockingType == AppBlockingType.Usage) Gson().toJson(viewModel.currentUsageConfig) else Gson().toJson(viewModel.currentTimeConfig)
        initialWarningConfig = Gson().toJson(viewModel.warningScrnConfig)
    }

    private fun loadExistingGroup(groupId: String) {
        lifecycleScope.launch {
            viewModel.keywordBlockerConfig.asFlow().collectLatest { config ->
                val group = config.keywordGroups.find { it.id == groupId }
                if (group != null && !isEditing) {
                    isEditing = true
                    isGroupActive = group.isActive
                    binding.tvTitle.text = "Edit Keyword Group"
                    binding.etGroupName.setText(group.name)
                    selectedKeywords = group.selectedKeywords.toMutableList()
                    
                    if (group.blockingType == AppBlockingType.Usage) {
                        binding.rbUsageBased.isChecked = true
                        viewModel.currentUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                    } else {
                        binding.rbTimedBased.isChecked = true
                        viewModel.currentTimeConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                    }

                    viewModel.warningScrnConfig = group.warningScreenConfig
                    
                    binding.btnDeleteGroup.visibility = View.VISIBLE
                    binding.btnDeleteGroup.setOnClickListener {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.delete_group)
                            .setMessage("Are you sure you want to delete this group?")
                            .setPositiveButton(R.string.delete) { _, _ ->
                                viewModel.deleteGroup(existingGroupId!!)
                                Toast.makeText(requireContext(), R.string.group_deleted, Toast.LENGTH_SHORT).show()
                                requireActivity().finish()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }

                    updateKeywordsList()
                    captureInitialState()
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty() && !selectedKeywords.contains(kw)) {
                selectedKeywords.add(kw)
                updateKeywordsList()
                binding.etKeyword.setText("")
            } else if (kw.isEmpty()) {
                Toast.makeText(requireContext(), R.string.write_a_new_keyword, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnImport.setOnClickListener { importLauncher.launch("*/*") }
        binding.btnExport.setOnClickListener { exportKeywordsToFile() }

        binding.btnConfigureBlocking.setOnClickListener {
            val type = if (binding.rbUsageBased.isChecked) AppBlockingType.Usage else AppBlockingType.Timed
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", if (type == AppBlockingType.Usage) KeywordUsageBasedSettingsFragment.FRAGMENT_ID else KeywordTimeBasedSettingsFragment.FRAGMENT_ID)
                putExtra("mode", "KEYWORD_BLOCKER")
            }
            startActivity(intent)
        }

        binding.btnConfigureWarning.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.FRAGMENT_ID)
                putExtra(neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.ARG_CONFIG, Gson().toJson(viewModel.warningScrnConfig))
                putExtra("mode", "KEYWORD_BLOCKER")
            }
            startActivity(intent)
        }

        binding.btnDone.setOnClickListener { saveGroup() }
    }

    private fun importKeywordsFromFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
            val imported = content.split("\n")
                .map { it.trim() }
                .filter { it.isNotEmpty() && !selectedKeywords.contains(it) }
            
            if (imported.isNotEmpty()) {
                selectedKeywords.addAll(imported)
                updateKeywordsList()
                Toast.makeText(requireContext(), "Imported ${imported.size} keywords", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Failed to import keywords", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportKeywordsToFile() {
        // Implementation for export
    }

    private fun updateKeywordsList() {
        binding.rvKeywords.adapter = keywordAdapter
        keywordAdapter.submitList(selectedKeywords.toList())
        binding.tvEmptyKeywords.visibility = if (selectedKeywords.isEmpty()) View.VISIBLE else View.GONE
    }

    inner class KeywordAdapter : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        private var items = listOf<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvKeyword: android.widget.TextView = view.findViewById(android.R.id.text1)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_1, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val kw = items[position]
            holder.tvKeyword.text = kw
            holder.itemView.setOnLongClickListener {
                selectedKeywords.removeAt(position)
                updateKeywordsList()
                true
            }
        }

        override fun getItemCount() = items.size
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = getString(R.string.enter_group_name)
            return
        }
        if (selectedKeywords.isEmpty()) {
            Toast.makeText(requireContext(), "Add at least one keyword", Toast.LENGTH_SHORT).show()
            return
        }

        val blockingType = if (binding.rbUsageBased.isChecked) AppBlockingType.Usage else AppBlockingType.Timed
        val group = KeywordGroup(
            id = existingGroupId ?: UUID.randomUUID().toString(),
            name = name,
            selectedKeywords = selectedKeywords.toList(),
            blockingType = blockingType,
            isActive = isGroupActive,
            setting = if (blockingType == AppBlockingType.Usage) Gson().toJson(viewModel.currentUsageConfig) else Gson().toJson(viewModel.currentTimeConfig),
            warningScreenConfig = viewModel.warningScrnConfig
        )

        if (existingGroupId == null) {
            viewModel.addGroup(group)
        } else {
            viewModel.updateGroupById(group)
        }
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
