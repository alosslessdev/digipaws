package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.viewBlocker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.BridgeServiceManager
import neth.iecal.curbox.databinding.FragmentViewBlockerBinding


class ViewBlockerFragment : Fragment() {

    private var _binding: FragmentViewBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ViewBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentViewBlockerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.switchEnableViewBlocker.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setIsActive(isChecked)
            }
        }

        binding.btnAddRule.setOnClickListener {
            val ruleText = binding.editCustomRule.text?.toString()?.trim() ?: ""
            if (ruleText.isEmpty() || !ruleText.contains("##")) {
                Toast.makeText(requireContext(), "Rule must be in format: package##key=value", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.addCustomRule(ruleText)
            binding.editCustomRule.text?.clear()
        }

        binding.btnPickElement.setOnClickListener {
            val intent = Intent(INTENT_ACTION_SHOW_PICKER_NOTIFICATION)
            intent.setPackage(requireContext().packageName)
            BridgeServiceManager.sendBridgedBroadcast(requireContext(), intent)
            Toast.makeText(requireContext(), "Notification shown. Switch to any app and tap the notification to start picking.", Toast.LENGTH_LONG).show()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.viewBlockerConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.switchEnableViewBlocker.isChecked != config.isActive) {
                    binding.switchEnableViewBlocker.isChecked = config.isActive
                }

                buildRuleToggles(config.rules)
                buildCustomRuleChips(config.customRules)

                isUpdatingUi = false
            }
        }
    }

    private fun getAppNameIfInstalled(context: Context, packageName: String): String? {
        val pm = context.getPackageManager()
        try {
            val ai = pm.getApplicationInfo(packageName, 0)
            return pm.getApplicationLabel(ai).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            return null // not installed
        }
    }
    private fun buildRuleToggles(rules: List<neth.iecal.curbox.data.models.ViewBlockerRule>) {
        val container = binding.rulesContainer
        container.removeAllViews()

        var currentPackage = ""
        var pm = requireContext().packageManager
        for (rule in rules) {
            if (rule.packageName != currentPackage) {
                val appName = getAppNameIfInstalled(requireContext(),rule.packageName) ?: continue
                currentPackage = rule.packageName
                val header = android.widget.TextView(requireContext()).apply {
                    text = appName
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                    val typedValue = android.util.TypedValue()
                    context.theme.resolveAttribute(
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                        typedValue, true
                    )
                    setTextColor(context.getColor(typedValue.resourceId))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        topMargin = (16 * resources.displayMetrics.density).toInt()
                        bottomMargin = (8 * resources.displayMetrics.density).toInt()
                    }
                }
                container.addView(header)
            }

            val toggle = MaterialSwitch(requireContext()).apply {
                text = rule.label
                isChecked = rule.isEnabled
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (4 * resources.displayMetrics.density).toInt()
                }
                setOnCheckedChangeListener { _, isChecked ->
                    if (!isUpdatingUi) {
                        viewModel.setRuleEnabled(rule.id, isChecked)
                    }
                }
            }
            container.addView(toggle)
        }
    }

    private fun buildCustomRuleChips(customRules: List<String>) {
        val chipGroup = binding.chipGroupCustomRules
        chipGroup.removeAllViews()

        for (rule in customRules) {
            val label = extractLabel(rule)
            val chip = Chip(requireContext()).apply {
                text = label
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    viewModel.removeCustomRule(rule)
                }
                setOnClickListener {
                    val editText = android.widget.EditText(requireContext())
                    editText.setText(rule)
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle("Edit Rule")
                        .setView(editText)
                        .setPositiveButton("Save") { _, _ ->
                            viewModel.removeCustomRule(rule)
                            viewModel.addCustomRule(editText.text.toString())
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            chipGroup.addView(chip)
        }
    }

    private fun extractLabel(ruleString: String): String {
        val parts = ruleString.split("##")
        for (part in parts) {
            if (part.startsWith("comment=")) {
                return part.removePrefix("comment=")
            }
        }
        // Fallback: show package + first selector
        val pkg = parts.getOrNull(0)?.substringAfterLast(".") ?: "rule"
        val selector = parts.getOrNull(1) ?: ""
        return "$pkg: $selector"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "view_blocker"
        const val INTENT_ACTION_SHOW_PICKER_NOTIFICATION = "neth.iecal.curbox.show.pickernoti"
    }
}
