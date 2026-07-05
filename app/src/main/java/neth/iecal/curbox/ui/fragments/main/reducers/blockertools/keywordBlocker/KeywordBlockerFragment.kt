package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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

class KeywordBlockerFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"
    }

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

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
        dialogBinding.cbSearchRecursively.isChecked = config.searchRecursively
        dialogBinding.cbMatchSubstrings.isChecked = config.matchSubstrings
        isUpdatingUi = false

        dialogBinding.cbSearchRecursively.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setSearchRecursively(isChecked)
        }

        dialogBinding.cbMatchSubstrings.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) viewModel.setMatchSubstrings(isChecked)
        }

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
