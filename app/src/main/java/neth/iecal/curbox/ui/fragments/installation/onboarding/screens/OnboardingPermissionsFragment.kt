package neth.iecal.curbox.ui.fragments.installation.onboarding.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.FragmentOnboardingPermissionsBinding
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppBlockingType
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.UsageTrackingService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingViewModel
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerSettingViewModel
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.utils.PermissionUtils
import neth.iecal.curbox.utils.ZipUtils
import neth.iecal.curbox.utils.ZipUtils.unzipSharedPreferencesFromUri
import java.util.UUID

class OnboardingPermissionsFragment : Fragment() {

    private var _binding: FragmentOnboardingPermissionsBinding? = null
    private val binding get() = _binding!!

    private val onboardingViewModel: OnboardingViewModel by activityViewModels()
    private val appBlockerViewModel: AppBlockerSettingViewModel by activityViewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            updatePermissionsState()
        }

    private val shizukuPermissionListener = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == 1001 && grantResult == PackageManager.PERMISSION_GRANTED) {
            activity?.runOnUiThread {
                runShizukuGrantAllCommand()
            }
        }
    }

    private val restorePicker: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri ->
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                activity?.contentResolver?.takePersistableUriPermission(uri, takeFlags)
                unzipSharedPreferencesFromUri(requireContext(), uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentOnboardingPermissionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnAction.setOnClickListener {
            // Persist onboarding app block group
            val targetApp = onboardingViewModel.targetAppPackage.value
            val limit = onboardingViewModel.dailyLimitMinutes.value ?: 30L
            
            val packageMap = mapOf(
                "Instagram" to "com.instagram.android",
                "TikTok" to "com.zhiliaoapp.musically",
                "YouTube" to "com.google.android.youtube",
                "Reddit" to "com.reddit.frontpage"
            )
            
            val pkg = packageMap[targetApp]
            if (pkg != null) {
                val usageConfig = AppUsageConfig(
                    isDailyUniform = true,
                    uniformLimit = limit,
                    dailyLimits = LongArray(7) { limit }
                )
                val newGroup = AppGroup(
                    id = UUID.randomUUID().toString(),
                    name = "$targetApp Limits",
                    selectedPackages = listOf(pkg),
                    blockingType = AppBlockingType.Usage,
                    isActive = true,
                    setting = Gson().toJson(usageConfig),
                    warningScreenConfig = AppBlockerWarningScreenConfig()
                )
                appBlockerViewModel.addGroup(newGroup)
            }

            val sharedPreferences =
                requireContext().getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
            sharedPreferences.edit().putBoolean("isFirstLaunchComplete", true).apply()

            val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
                putExtra("fragment", AllAppsUsageFragment.FRAGMENT_ID)
            }
            startActivity(intent)
        }

        binding.overlayPermRoot.setOnClickListener {
            if (Settings.canDrawOverlays(requireContext())) return@setOnClickListener
            showExplanationDialog(
                title = "Screen Overlay",
                rationale = "Curbox needs this to show a calm pause screen on top of distracting apps when you open them. Without it, Curbox cannot place anything over those apps to help you stop and think.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Open Source: Think of Curbox like a restaurant with an open kitchen. The whole codebase is public, so anyone can check that Curbox is not doing anything sneaky. There are no closed doors here."
            ) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${requireContext().packageName}")
                }
                startActivity(intent)
            }
        }

        binding.notifPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())) return@setOnClickListener
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                showExplanationDialog(
                    title = "Notifications",
                    rationale = "Curbox needs this to stay running in the background so your blocks keep working, and to gently remind you of your goals. Without it, Android can stop Curbox and your blocks may fail.",
                    openSourceExplanation = "\uD83D\uDEE1\uFE0F Not a Data Broker: Many apps hide their code because they make money by harvesting your data. Curbox keeps all its code public, so you can check yourself that nothing is quietly sending your information away."
                ) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }

        binding.dndPermRoot.setOnClickListener {
            val notificationManager = requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (notificationManager.isNotificationPolicyAccessGranted) return@setOnClickListener
            
            showExplanationDialog(
                title = "Do Not Disturb",
                rationale = "Curbox needs this to turn on Do Not Disturb for you, so it can mute calls and alerts while you focus. Without it, Curbox cannot silence distractions on its own.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Curbox respects your peace: It uses this only to mute distractions when you ask it to."
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    startActivity(intent)
                }
            }
        }

        binding.blockerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = "App Blocker (Accessibility API)",
                rationale = "Curbox needs this to notice when you open a blocked app and show the blocker screen. Without it, app blocking cannot work at all.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Transparency for Deep Access: This is a powerful permission, which is why being open source matters so much. You do not have to take Curbox at its word. The global community has reviewed its public code and confirmed it only blocks apps."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),AppBlockerService::class.java)
            }
        }

        binding.trackerAccPermRoot.setOnClickListener {
            if (neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)) return@setOnClickListener
            showExplanationDialog(
                title = "Usage Tracker (Accessibility API)",
                rationale = "Curbox needs this to count how many reels and short videos you scroll and show you mindful nudges. Without it, usage tracking cannot work at all.",
                openSourceExplanation = "\uD83D\uDEE1\uFE0F Transparency for Deep Access: This is a powerful permission, which is why being open source matters so much. You do not have to take Curbox at its word. The global community has reviewed its public code and confirmed it only tracks usage."
            ) {
                PermissionUtils.openAccessibilityServiceScreen(requireContext(),UsageTrackingService::class.java)
            }
        }

        binding.btnShowRestrictedTutorial.setOnClickListener {
            val manufacturer = Build.MANUFACTURER
            val query = Uri.encode("How to enable restricted setting on $manufacturer android 13")
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$query"))
            startActivity(intent)
        }


        binding.btnShizukuGrantAll.setOnClickListener {
            if (!neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()) {
                try {
                    rikka.shizuku.Shizuku.requestPermission(1001)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                runShizukuGrantAllCommand()
            }
        }

        setupDescText()
        updatePermissionsState()
    }

    private fun setupDescText() {
        val baseText = getString(R.string.to_create_friction_and_give_you)
        val actionText = " Read Documentation"
        val fullText = "$baseText $actionText"
        val spannableString = SpannableString(fullText)

        val clickableSpan = object : ClickableSpan() {
            override fun onClick(view: View) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://curbox.app/docs"))
                startActivity(intent)
            }
        }

        val start = fullText.indexOf(actionText)
        val end = start + actionText.length

        spannableString.setSpan(clickableSpan, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannableString.setSpan(
            ForegroundColorSpan(MaterialColors.getColor(binding.desc, com.google.android.material.R.attr.colorPrimary)),
            start,
            end,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        spannableString.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        binding.desc.text = spannableString
        binding.desc.movementMethod = LinkMovementMethod.getInstance()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            updatePermissionsState()
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            try {
                rikka.shizuku.Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    private fun showExplanationDialog(title: String, rationale: String, openSourceExplanation: String, onProceed: () -> Unit) {
        val privacy = "\n\n\uD83D\uDD12 100% Private: Curbox does not collect, send, or store any of your data on a server. Everything stays on your phone.\n\n"
        
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(rationale + privacy + openSourceExplanation)
            .setPositiveButton("Proceed") { _, _ -> onProceed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runShizukuGrantAllCommand() {
        binding.btnShizukuGrantAll.isEnabled = false
        binding.btnShizukuGrantAll.text = "Granting Permissions..."

        val pkg = requireContext().packageName
        val svc1 = "$pkg/${AppBlockerService::class.java.name}"
        val svc2 = "$pkg/${UsageTrackingService::class.java.name}"

        val command = """
            appops set $pkg SYSTEM_ALERT_WINDOW allow
            pm grant $pkg android.permission.POST_NOTIFICATIONS
            cmd notification allow_dnd $pkg
            
            CURRENT_ACC_SVCS=${'$'}(settings get secure enabled_accessibility_services)
            if [ "${'$'}CURRENT_ACC_SVCS" = "null" ] || [ -z "${'$'}CURRENT_ACC_SVCS" ]; then
                settings put secure enabled_accessibility_services "$svc1:$svc2"
            else
                NEW_SVCS="${'$'}CURRENT_ACC_SVCS"
                case "${'$'}CURRENT_ACC_SVCS" in
                    *"$svc1"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc1" ;;
                esac
                case "${'$'}NEW_SVCS" in
                    *"$svc2"*) ;;
                    *) NEW_SVCS="${'$'}NEW_SVCS:$svc2" ;;
                esac
                settings put secure enabled_accessibility_services "${'$'}NEW_SVCS"
            fi
            settings put secure accessibility_enabled 1
        """.trimIndent()

        neth.iecal.curbox.utils.ShizukuRunner.executeCommand(command, object : neth.iecal.curbox.utils.ShizukuRunner.CommandResultListener {
            override fun onCommandResult(output: String, done: Boolean) {
                if (done) {
                    activity?.runOnUiThread {
                        binding.btnShizukuGrantAll.text = "Permissions Granted!"
                        binding.btnShizukuGrantAll.isEnabled = true
                        updatePermissionsState()
                    }
                }
            }

            override fun onCommandError(error: String) {
                activity?.runOnUiThread {
                    binding.btnShizukuGrantAll.isEnabled = true
                    binding.btnShizukuGrantAll.text = "Error, Tap to Retry"
                    updatePermissionsState()
                }
            }
        })
    }

    private fun updatePermissionsState() {
        val hasOverlay = Settings.canDrawOverlays(requireContext())
        val hasNotif = neth.iecal.curbox.utils.PermissionUtils.isNotificationPermissionGiven(requireContext())
        val hasDnd = (requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).isNotificationPolicyAccessGranted
        val hasBlocker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), AppBlockerService::class.java)
        val hasTracker = neth.iecal.curbox.utils.PermissionUtils.isAccessibilityServiceEnabled(requireContext(), UsageTrackingService::class.java)
        val hasShizuku = neth.iecal.curbox.utils.PermissionUtils.hasShizukuPermission()

        val isNonSession = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val info = requireContext().packageManager.getInstallSourceInfo(requireContext().packageName)
                val initiatingPackage = info.initiatingPackageName
                initiatingPackage != "com.android.vending" && initiatingPackage != "org.fdroid.fdroid"
            } catch (e: Exception) {
                false
            }
        } else {
            false
        }

        val showRestrictedWarning = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && (!hasBlocker || !hasTracker) && isNonSession
        binding.restrictedSettingsWarning.visibility = if (showRestrictedWarning) View.VISIBLE else View.GONE
        
        if (neth.iecal.curbox.utils.PermissionUtils.isShizukuAvailable()) {
            binding.btnShizukuGrantAll.visibility = View.VISIBLE
        } else {
            binding.btnShizukuGrantAll.visibility = View.GONE
        }

        setPermissionIcon(hasOverlay, binding.overlayPermIcon)
        setPermissionIcon(hasNotif, binding.notifPermIcon)
        setPermissionIcon(hasDnd, binding.dndPermIcon)
        setPermissionIcon(hasBlocker, binding.blockerAccPermIcon)
        setPermissionIcon(hasTracker, binding.trackerAccPermIcon)

        // Enforce Sequence
        binding.overlayPermRoot.isEnabled = !hasOverlay
        binding.overlayPermRoot.alpha = if (hasOverlay) 0.5f else 1.0f

        val canDoNotif = hasOverlay
        binding.notifPermRoot.isEnabled = canDoNotif && !hasNotif
        binding.notifPermRoot.alpha = if (canDoNotif) (if (hasNotif) 0.5f else 1.0f) else 0.3f

        val canDoDnd = canDoNotif && hasNotif
        binding.dndPermRoot.isEnabled = canDoDnd && !hasDnd
        binding.dndPermRoot.alpha = if (canDoDnd) (if (hasDnd) 0.5f else 1.0f) else 0.3f

        val canDoBlocker = canDoDnd && hasDnd
        binding.blockerAccPermRoot.isEnabled = canDoBlocker && !hasBlocker
        binding.blockerAccPermRoot.alpha = if (canDoBlocker) (if (hasBlocker) 0.5f else 1.0f) else 0.3f

        val canDoTracker = canDoBlocker && hasBlocker
        binding.trackerAccPermRoot.isEnabled = canDoTracker && !hasTracker
        binding.trackerAccPermRoot.alpha = if (canDoTracker) (if (hasTracker) 0.5f else 1.0f) else 0.3f

        val allGranted = hasOverlay && hasNotif && hasDnd && hasBlocker && hasTracker
        binding.btnAction.isEnabled = allGranted
        if (allGranted) {
            binding.btnAction.text = "Curb me!"
        } else {
            binding.btnAction.text = "I still need more permissions"
        }
    }

    private fun setPermissionIcon(isEnabled: Boolean, icon: ImageView) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.baseline_done_24)
            icon.setColorFilter(resources.getColor(R.color.md_theme_onSurface, requireContext().theme))
        } else {
            icon.setImageResource(R.drawable.baseline_close_24)
            icon.setColorFilter(resources.getColor(R.color.error_color, requireContext().theme))
        }
    }
}
