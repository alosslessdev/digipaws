package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.*
import neth.iecal.curbox.databinding.FragmentCreateKeywordGroupBinding
import neth.iecal.curbox.utils.ViewUtils
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.*

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

    private val importLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { importKeywordsFromFile(it) }
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri?.let { exportKeywordsToFile(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCreateKeywordGroupBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupBlockingTypeSelection()
        
        binding.rvKeywords.adapter = keywordAdapter
        
        existingGroupId = requireActivity().intent.getStringExtra("group_id") ?: arguments?.getString("group_id")
        
        if (existingGroupId != null) {
            loadExistingGroup(existingGroupId!!)
        }

        setupListeners()
    }

    private fun loadExistingGroup(groupId: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                val group = config.keywordGroups.find { it.id == groupId }
                if (group != null && !isEditing) {
                    isEditing = true
                    binding.tvTitle.text = "Edit Keyword Group"
                    binding.etGroupName.setText(group.name)
                    selectedKeywords = group.selectedKeywords.toMutableList()
                    updateKeywordsList()
                    
                    if (group.blockingType == AppBlockingType.Usage) {
                        selectBlockingType(binding.rbUsageBased)
                        viewModel.currentUsageConfig = Gson().fromJson(group.setting, AppUsageConfig::class.java)
                    } else {
                        selectBlockingType(binding.rbTimeBased)
                        viewModel.currentTimeConfig = Gson().fromJson(group.setting, AppTimeConfig::class.java)
                    }

                    viewModel.warningScrnConfig = group.warningScreenConfig
                }
            }
        }
    }

    private fun setupBlockingTypeSelection() {
        val radioButtons = listOf(binding.rbUsageBased, binding.rbTimeBased)

        radioButtons.forEach { rb ->
            rb.setOnClickListener { selectBlockingType(rb) }
        }

        binding.btnHelpUsage.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Set a daily time limit for these keywords. Once reached, they will be blocked for the rest of the day.", "https://curbox.app/docs/reducers/keyword-blocker/")
        }

        binding.btnHelpTime.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Allow these keywords only during specific time intervals during the day (e.g., during work hours). They stay blocked the rest of the time.", "https://curbox.app/docs/reducers/keyword-blocker/")
        }
    }

    private fun selectBlockingType(selected: View) {
        binding.rbUsageBased.isChecked = selected == binding.rbUsageBased
        binding.rbTimeBased.isChecked = selected == binding.rbTimeBased
    }

    private fun setupListeners() {
        binding.btnAddKeyword.setOnClickListener {
            val kw = binding.etKeyword.text.toString().trim()
            if (kw.isNotEmpty() && !selectedKeywords.contains(kw)) {
                selectedKeywords.add(kw)
                updateKeywordsList()
                binding.etKeyword.setText("")
            }
        }

        binding.btnConfigureSettings.setOnClickListener {
            if (binding.rbUsageBased.isChecked) {
                KeywordUsageBasedSettingsFragment().show(parentFragmentManager, KeywordUsageBasedSettingsFragment.FRAGMENT_ID)
            } else {
                KeywordTimeBasedSettingsFragment().show(parentFragmentManager, KeywordTimeBasedSettingsFragment.FRAGMENT_ID)
            }
        }

        binding.btnConfigureWarningScreen.setOnClickListener {
            val configFragment = neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(
                viewModel.warningScrnConfig, 
                "result_warning_config",
                isNew = existingGroupId == null
            )
            parentFragmentManager.beginTransaction()
                .hide(this)
                .add(R.id.fragment_holder, configFragment)
                .addToBackStack(null)
                .commit()
        }

        parentFragmentManager.setFragmentResultListener("result_warning_config", viewLifecycleOwner) { _, bundle ->
            bundle.getString("result_config")?.let {
                viewModel.warningScrnConfig = Gson().fromJson(it, AppBlockerWarningScreenConfig::class.java)
            }
        }

        binding.btnMoreOptions.setOnClickListener {
            showMoreOptions(it)
        }

        binding.fabSaveGroup.setOnClickListener { saveGroup() }
    }

    private fun showMoreOptions(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_group_options, popup.menu)
        
        if (existingGroupId == null) {
            popup.menu.findItem(R.id.action_delete)?.isVisible = false
        }

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_import -> {
                    importLauncher.launch("text/plain")
                    true
                }
                R.id.action_export -> {
                    val fileName = "keywords_${binding.etGroupName.text.toString().ifEmpty { "group" }}.txt"
                    exportLauncher.launch(fileName)
                    true
                }
                R.id.action_delete -> {
                    existingGroupId?.let { viewModel.deleteGroup(it) }
                    requireActivity().finish()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun importKeywordsFromFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val keywords = withContext(Dispatchers.IO) {
                    val list = mutableListOf<String>()
                    requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                        BufferedReader(InputStreamReader(inputStream)).use { reader ->
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                line?.trim()?.let {
                                    if (it.isNotEmpty()) list.add(it)
                                }
                            }
                        }
                    }
                    list
                }
                
                var addedCount = 0
                keywords.forEach { kw ->
                    if (!selectedKeywords.contains(kw)) {
                        selectedKeywords.add(kw)
                        addedCount++
                    }
                }
                
                if (addedCount > 0) {
                    updateKeywordsList()
                    Toast.makeText(requireContext(), "Imported $addedCount keywords", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "No new keywords to import", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to import keywords", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun exportKeywordsToFile(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    requireContext().contentResolver.openOutputStream(uri)?.use { outputStream ->
                        outputStream.write(selectedKeywords.joinToString("\n").toByteArray())
                    }
                }
                Toast.makeText(requireContext(), "Keywords exported successfully", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to export keywords", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateKeywordsList() {
        keywordAdapter.submitList(selectedKeywords.toList())
    }

    inner class KeywordAdapter : RecyclerView.Adapter<KeywordAdapter.ViewHolder>() {
        private var items = listOf<String>()

        fun submitList(newItems: List<String>) {
            items = newItems
            notifyDataSetChanged()
        }

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
                    selectedKeywords.removeAt(currentPos)
                    updateKeywordsList()
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private fun saveGroup() {
        val name = binding.etGroupName.text.toString().trim()
        if (name.isEmpty()) {
            binding.etGroupName.error = "Enter group name"
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
            isActive = true,
            setting = if (blockingType == AppBlockingType.Usage) Gson().toJson(viewModel.currentUsageConfig) else Gson().toJson(viewModel.currentTimeConfig),
            warningScreenConfig = viewModel.warningScrnConfig
        )

        if (existingGroupId != null) viewModel.updateGroupById(group) else viewModel.addGroup(group)
        requireActivity().finish()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
