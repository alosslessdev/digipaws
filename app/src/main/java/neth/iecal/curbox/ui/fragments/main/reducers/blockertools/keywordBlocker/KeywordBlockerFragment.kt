package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker

import neth.iecal.curbox.R

import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.databinding.FragmentKeywordBlockerBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity

class KeywordBlockerFragment : Fragment() {

    private var _binding: FragmentKeywordBlockerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: KeywordBlockerViewModel by activityViewModels()
    private var isUpdatingUi = false

    private var selectedApps = listOf<String>()
    private val selectAppsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == AppCompatActivity.RESULT_OK) {
            val apps = result.data?.getStringArrayListExtra("SELECTED_APPS")
            if (apps != null) {
                viewModel.setIgnoredApps(apps)
                binding.btnSelectIgnoredApps.text = "Select Ignored Apps (${apps.size})"
                selectedApps = apps
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
        setupListeners()
        observeViewModel()
    }

    private fun setupListeners() {
        binding.switchEnableBlocker.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setIsActive(isChecked)
            }
        }

        binding.btnAddKeyword.setOnClickListener {
            var keyword = binding.etKeyword.text.toString()
            if (keyword.isNotBlank()) {
                if (Patterns.WEB_URL.matcher(keyword).matches()) {
                    keyword = keyword
                        .removePrefix("https://")
                        .removePrefix("http://")
                        .removePrefix("www.")
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.warning_link_blocker_may_not_work),
                        Toast.LENGTH_LONG
                    ).show()
                }
                viewModel.addKeyword(keyword)
                binding.etKeyword.setText("")
            }
        }

        binding.etRedirectUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isUpdatingUi) {
                    viewModel.setRedirectUrl(s.toString())
                }
            }
        })

        binding.cbSearchRecursively.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setSearchRecursively(isChecked)
            }
        }

        binding.cbMatchSubstrings.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setMatchSubstrings(isChecked)
            }
        }

        binding.cbBlockUnsupportedBrowsers.setOnCheckedChangeListener { _, isChecked ->
            if (!isUpdatingUi) {
                viewModel.setBlockAllExceptSupported(isChecked)
            }
        }

        binding.btnSelectIgnoredApps.setOnClickListener {
            val intent = Intent(requireContext(), SelectAppsActivity::class.java)
            intent.putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(selectedApps))
            selectAppsLauncher.launch(intent)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.keywordBlockerConfig.collectLatest { config ->
                isUpdatingUi = true

                if (binding.switchEnableBlocker.isChecked != config.isActive) {
                    binding.switchEnableBlocker.isChecked = config.isActive
                }

                if (binding.etRedirectUrl.text.toString() != config.redirectUrl) {
                    binding.etRedirectUrl.setText(config.redirectUrl)
                }

                if (binding.cbSearchRecursively.isChecked != config.searchRecursively) {
                    binding.cbSearchRecursively.isChecked = config.searchRecursively
                }

                if (binding.cbMatchSubstrings.isChecked != config.matchSubstrings) {
                    binding.cbMatchSubstrings.isChecked = config.matchSubstrings
                }

                if (binding.cbBlockUnsupportedBrowsers.isChecked != config.blockAllExceptSupported) {
                    binding.cbBlockUnsupportedBrowsers.isChecked = config.blockAllExceptSupported
                }
                selectedApps = config.ignoredApps

                updateKeywordsList(config.blockedKeywords)

                isUpdatingUi = false
            }
        }
    }

    private fun updateKeywordsList(keywords: List<String>) {
        binding.cgKeywords.removeAllViews()
        for (keyword in keywords) {
            val chip = Chip(requireContext()).apply {
                text = keyword
                isCloseIconVisible = true
                setOnCloseIconClickListener {
                    showRemoveConfirmation(keyword)
                }
            }
            binding.cgKeywords.addView(chip)
        }
    }

    private fun showRemoveConfirmation(keyword: String) {
        var countdownTimer: CountDownTimer? = null

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.remove_entry)
            .setMessage(getString(R.string.remove_entry_confirmation, keyword))
            .setPositiveButton(R.string.yes, null)
            .setNegativeButton(R.string.no, null)
            .create()

        dialog.setOnShowListener {
            val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveButton.isEnabled = false
            positiveButton.text = getString(R.string.yes_in_seconds, 20)
            positiveButton.setOnClickListener {
                viewModel.removeKeyword(keyword)
                dialog.dismiss()
            }

            countdownTimer = object : CountDownTimer(20_000L, 1_000L) {
                override fun onTick(millisUntilFinished: Long) {
                    val secondsRemaining = (millisUntilFinished / 1_000L).toInt()
                    positiveButton.text = getString(R.string.yes_in_seconds, secondsRemaining)
                }

                override fun onFinish() {
                    positiveButton.isEnabled = true
                    positiveButton.text = getString(R.string.yes)
                }
            }.start()
        }

        dialog.setOnDismissListener {
            countdownTimer?.cancel()
        }

        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "keyword_blocker"
    }
}
