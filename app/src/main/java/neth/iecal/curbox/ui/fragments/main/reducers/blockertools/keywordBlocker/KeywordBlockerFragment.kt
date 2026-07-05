package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.databinding.DialogKeywordSettingsBinding
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class KeywordBlockerFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"
    }

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false
    private var selectedApps: List<String> = emptyList()

    private val selectAppsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
                if (apps != null) {
                    viewModel.setIgnoredApps(apps)
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeywordBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val currentConfig = viewModel.keywordBlockerConfig.value
        if (currentConfig == null || !currentConfig.isActive) {
            viewModel.setIsActive(true)
        }

        binding.rvKeywordGroups.layoutManager = LinearLayoutManager(requireContext())
        binding.fabAddGroup.setOnClickListener {
            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.btnMenu.setOnClickListener { showPopupMenu(it) }

        observeViewModel()
    }

    private fun showPopupMenu(view: View) {
        val popup = PopupMenu(requireContext(), view)
        popup.menuInflater.inflate(R.menu.menu_keyword_blocker, popup.menu)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_settings -> {
                    showSettingsDialog()
                    true
                }
                R.id.menu_help -> {
                    val url = "https://curbox.app/docs/reducers/keyword-blocker/"
                    try {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(browserIntent)
                    } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    private fun showSettingsDialog() {
        val dialogBinding = DialogKeywordSettingsBinding.inflate(layoutInflater)
        val config = viewModel.keywordBlockerConfig.value ?: return
        
        isUpdatingUi = true
        dialogBinding.etRedirectUrl.setText(config.redirectUrl)
        dialogBinding.cbSearchRecursively.isChecked = config.searchRecursively
        dialogBinding.cbMatchSubstrings.isChecked = config.matchSubstrings
        dialogBinding.cbBlockUnsupportedBrowsers.isChecked = config.blockAllExceptSupported
        selectedApps = config.ignoredApps
        dialogBinding.switchTimeTracking.isChecked = config.isTimeTrackingEnabled
        dialogBinding.layoutTimeTrackingSettings.visibility = if (config.isTimeTrackingEnabled) View.VISIBLE else View.GONE
        dialogBinding.etClusteringThreshold.setText(config.clusteringThresholdMinutes.toString())
        isUpdatingUi = false

        dialogBinding.etRedirectUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) viewModel.setRedirectUrl(s.toString())
            }
        })

        dialogBinding.cbSearchRecursively.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setSearchRecursively(isChecked)
        }

        dialogBinding.cbMatchSubstrings.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setMatchSubstrings(isChecked)
        }

        dialogBinding.cbBlockUnsupportedBrowsers.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setBlockAllExceptSupported(isChecked)
        }

        dialogBinding.btnSelectIgnoredApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedApps))
            selectAppsLauncher.launch(intent)
        }

        dialogBinding.switchTimeTracking.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setTimeTrackingEnabled(isChecked)
                dialogBinding.layoutTimeTrackingSettings.visibility = if (isChecked) View.VISIBLE else View.GONE
            }
        }

        dialogBinding.etClusteringThreshold.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    val threshold = s.toString().toIntOrNull() ?: 5
                    viewModel.setClusteringThreshold(threshold)
                }
            }
        })

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.advanced)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.done, null)
            .show()
    }

    private fun observeViewModel() {
        viewModel.keywordBlockerConfig.observe(viewLifecycleOwner) { config ->
            if (config.keywordGroups.isEmpty()) {
                binding.tvEmptyState.visibility = View.VISIBLE
                binding.rvKeywordGroups.visibility = View.GONE
            } else {
                binding.tvEmptyState.visibility = View.GONE
                binding.rvKeywordGroups.visibility = View.VISIBLE
                binding.rvKeywordGroups.adapter = KeywordGroupAdapter(config.keywordGroups)
            }
            selectedApps = config.ignoredApps
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class KeywordGroupAdapter(private val groups: List<KeywordGroup>) :
        RecyclerView.Adapter<KeywordGroupAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tv_group_name)
            val tvDetails: TextView = view.findViewById(R.id.tv_group_details)
            val tvRemaining: TextView = view.findViewById(R.id.tv_group_remaining)
            val switchActive: SwitchMaterial = view.findViewById(R.id.switch_group_active)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_group, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val group = groups[position]
            holder.tvName.text = group.name
            
            val typeStr = when (group.blockingType) {
                AppBlockingType.Usage -> getString(R.string.usage_based)
                AppBlockingType.Timed -> getString(R.string.time_based)
                AppBlockingType.OnOpen -> getString(R.string.label_on_each_open)
            }
            holder.tvDetails.text = "${group.selectedKeywords.size} Keywords • $typeStr"

            holder.switchActive.setOnCheckedChangeListener(null)
            holder.switchActive.isChecked = group.isActive
            holder.switchActive.setOnCheckedChangeListener { _, isChecked ->
                viewModel.updateGroupActiveState(group.id, isChecked)
            }

            holder.itemView.setOnClickListener {
                val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                    putExtra("fragment", CreateKeywordGroupFragment.FRAGMENT_ID)
                    putExtra("result_id", group.id)
                }
                startActivity(intent)
            }
        }

        override fun getItemCount() = groups.size
    }
}
