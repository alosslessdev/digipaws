package neth.iecal.curbox.ui.activity

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import neth.iecal.curbox.R
import neth.iecal.curbox.ui.fragments.installation.AccessibilityGuide
import neth.iecal.curbox.ui.fragments.installation.onboarding.OnboardingFragment
import neth.iecal.curbox.ui.fragments.main.focus.FocusFragment
import neth.iecal.curbox.ui.fragments.main.reducers.ReducersFragment
import neth.iecal.curbox.ui.fragments.main.reducers.analytics.IntentsLogFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.CreateGrayscaleGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleFragment
import neth.iecal.curbox.ui.fragments.main.usage.AllAppsUsageFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.AppBlockerGroupsFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.CreateAppGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.AutoDndFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.CreateAutoDndGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.reelBlocker.ReelBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordBlockerFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.CreateKeywordGroupFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderFragment
import neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider.UiHiderEditorFragment
import androidx.core.view.isVisible
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class FragmentActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val sharedPreferences = getSharedPreferences("AppPreferences", android.content.Context.MODE_PRIVATE)
        val isFirstLaunchComplete = sharedPreferences.getBoolean("isFirstLaunchComplete", false)
        val selectedFragmentStr = intent.getStringExtra("fragment") ?: intent.getStringExtra("fragment_type") ?: if (!isFirstLaunchComplete) OnboardingFragment.FRAGMENT_ID else AllAppsUsageFragment.FRAGMENT_ID
        val mode = intent.getStringExtra("mode")
        
        val selectedFragment = when (selectedFragmentStr) {
            "app_time_config" -> when (mode) {
                "GRAYSCALE" -> "grayscale_time_settings"
                "AUTODND" -> "autodnd_time_settings"
                "KEYWORD_BLOCKER" -> "keyword_time_based_settings"
                else -> "time_based_settings"
            }
            "app_usage_config" -> when (mode) {
                "KEYWORD_BLOCKER" -> "KeywordUsageBasedSettingsBottomSheet"
                else -> "usage_based_settings"
            }
            "warning_screen_config" -> "warning_config"
            else -> selectedFragmentStr
        }

        if (selectedFragment == OnboardingFragment.FRAGMENT_ID) {
            setTheme(R.style.Theme_Curbox_Onboarding)
        }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_fragment)

        maybeShowTermsConsent()

        val bottomNav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.bottom_nav)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            // If bottom navigation is visible, it handles the bottom system bar inset itself.
            // We only apply the bottom padding from system bars if the bottom nav is hidden.
            val bottomPadding = if (bottomNav.isVisible) {
                ime.bottom // Only pad for keyboard if nav is visible
            } else {
                maxOf(systemBars.bottom, ime.bottom)
            }

            v.setPadding(systemBars.left, systemBars.top, systemBars.right, bottomPadding)
            insets
        }

        when (selectedFragment) {
            OnboardingFragment.FRAGMENT_ID,
            AccessibilityGuide.FRAGMENT_ID,
            AppBlockerGroupsFragment.FRAGMENT_ID,
            CreateAppGroupFragment.FRAGMENT_ID,
            ReelBlockerFragment.FRAGMENT_ID,
            AutoDndFragment.FRAGMENT_ID,
            CreateAutoDndGroupFragment.FRAGMENT_ID,
            "reel_counter_fragment",
            GrayscaleFragment.FRAGMENT_ID,
            CreateGrayscaleGroupFragment.FRAGMENT_ID,
                UiHiderFragment.FRAGMENT_ID,
                UiHiderEditorFragment.FRAGMENT_ID,
                IntentsLogFragment.FRAGMENT_ID,
            "mindful_messages_fragment",
            KeywordBlockerFragment.FRAGMENT_ID,
            "api_fragment",
            CreateKeywordGroupFragment.FRAGMENT_ID,
            "time_based_settings",
            "usage_based_settings",
            "keyword_time_based_settings",
            "KeywordUsageBasedSettingsBottomSheet",
            "autodnd_time_settings",
            "grayscale_time_settings",
            "warning_config" -> {
                // Hide bottom nav for these standalone fragments
                bottomNav.visibility = android.view.View.GONE
                
                val fragment = when (selectedFragment) {
                    OnboardingFragment.FRAGMENT_ID -> OnboardingFragment()
                    AppBlockerGroupsFragment.FRAGMENT_ID -> AppBlockerGroupsFragment()
                    CreateAppGroupFragment.FRAGMENT_ID -> CreateAppGroupFragment()
                    ReelBlockerFragment.FRAGMENT_ID -> ReelBlockerFragment()
                    KeywordBlockerFragment.FRAGMENT_ID -> KeywordBlockerFragment()
                    CreateKeywordGroupFragment.FRAGMENT_ID -> CreateKeywordGroupFragment()
                    UiHiderFragment.FRAGMENT_ID -> UiHiderFragment()
                    UiHiderEditorFragment.FRAGMENT_ID -> UiHiderEditorFragment()
                    AutoDndFragment.FRAGMENT_ID -> AutoDndFragment()
                    CreateAutoDndGroupFragment.FRAGMENT_ID -> CreateAutoDndGroupFragment()
                    "reel_counter_fragment" -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.reel_counter.ReelCounterFragment()
                    GrayscaleFragment.FRAGMENT_ID -> GrayscaleFragment()
                    CreateGrayscaleGroupFragment.FRAGMENT_ID -> CreateGrayscaleGroupFragment()
                    "mindful_messages_fragment" -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.mindful_messages.MindfulMessagesFragment()
                    IntentsLogFragment.FRAGMENT_ID -> IntentsLogFragment()
                    "api_fragment" -> neth.iecal.curbox.ui.fragments.main.reducers.api.ApiFragment()
                    "time_based_settings" -> neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.TimeBasedSettingsFragment()
                    "usage_based_settings" -> neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker.UsageBasedSettingsFragment()
                    "keyword_time_based_settings" -> neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordTimeBasedSettingsFragment()
                    "KeywordUsageBasedSettingsBottomSheet" -> neth.iecal.curbox.ui.fragments.main.reducers.blockertools.keywordBlocker.KeywordUsageBasedSettingsFragment()
                    "autodnd_time_settings" -> neth.iecal.curbox.ui.fragments.main.reducers.blockertools.autodnd.AutoDndTimeSettingsFragment()
                    "grayscale_time_settings" -> neth.iecal.curbox.ui.fragments.main.reducers.anti_stimulants.grayscale.GrayscaleTimeSettingsFragment()
                    "warning_config" -> {
                        val configJson = intent.getStringExtra("arg_config")
                        val requestKey = intent.getStringExtra("arg_request_key") ?: "result_key"
                        val isNew = intent.getBooleanExtra("arg_is_new", false)
                        val isOnOpen = intent.getBooleanExtra("arg_is_on_open", false)
                        
                        if (configJson != null) {
                            val config = com.google.gson.Gson().fromJson(configJson, neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig::class.java)
                            neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment.newInstance(config, requestKey, isNew, isOnOpen)
                        } else {
                            neth.iecal.curbox.ui.fragments.main.reducers.blockertools.shared.WarningConfigFragment()
                        }
                    }
                    else -> AccessibilityGuide()
                }
                fragment.arguments = intent.extras

                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_holder, fragment)
                    .commit()
            }
            else -> {
                // Show bottom nav for main fragments
                bottomNav.visibility = android.view.View.VISIBLE

                neth.iecal.curbox.utils.DonationPrompt.maybeShow(this)

                if (savedInstanceState == null) {
                    bottomNav.selectedItemId = R.id.nav_usage
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_holder, AllAppsUsageFragment())
                        .commit()
                }
                
                bottomNav.setOnItemSelectedListener { item ->
                    if (item.itemId == bottomNav.selectedItemId) return@setOnItemSelectedListener false

                    val fragment = when (item.itemId) {
                        R.id.nav_usage -> AllAppsUsageFragment()
                        R.id.nav_focus -> FocusFragment()
                        R.id.nav_reducers -> ReducersFragment()
                        R.id.nav_info -> neth.iecal.curbox.ui.fragments.main.InfoFragment()
                        else -> AllAppsUsageFragment()
                    }
                    
                    switchFragment(fragment)
                    true
                }
            }
        }
    }

    private fun maybeShowTermsConsent() {
        val prefs = getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)
        if (prefs.getBoolean("hasAcceptedTerms", false)) return

        val view = layoutInflater.inflate(R.layout.dialog_terms_consent, null)
        view.findViewById<TextView>(R.id.terms_link).setOnClickListener {
            openUrl(getString(R.string.terms_url))
        }
        view.findViewById<TextView>(R.id.privacy_link).setOnClickListener {
            openUrl(getString(R.string.privacy_url))
        }

        MaterialAlertDialogBuilder(this)
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(R.string.terms_consent_agree) { _, _ ->
                prefs.edit().putBoolean("hasAcceptedTerms", true).apply()
            }
            .setNegativeButton(R.string.terms_consent_exit) { _, _ ->
                finishAffinity()
            }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun switchFragment(fragment: Fragment) {
        val container = findViewById<android.view.View>(R.id.fragment_holder)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val animator = ValueAnimator.ofFloat(0f, 1f)
            animator.duration = 500 // Slightly longer for a more "premium" feel

            var fragmentReplaced = false

            animator.addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                
                // Animate blur: 0 -> 40 -> 0
                val blurRadius = if (fraction < 0.5f) fraction * 2 * 40f else (1f - fraction) * 2 * 40f
                
                // Animate alpha: 1.0 -> 0.0 -> 1.0 (Full dip to 0 to hide the swap)
                val alphaValue = if (fraction < 0.5f) 1f - (fraction * 2f) else (fraction - 0.5f) * 2f

                container.alpha = alphaValue
                
                if (blurRadius > 0.1f) {
                    container.setRenderEffect(
                        RenderEffect.createBlurEffect(
                            blurRadius, blurRadius, Shader.TileMode.CLAMP
                        )
                    )
                } else {
                    container.setRenderEffect(null)
                }

                if (fraction >= 0.5f && !fragmentReplaced) {
                    fragmentReplaced = true
                    // Remove the built-in fade animation here to avoid conflict with our manual alpha animation
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_holder, fragment)
                        .commitNow()
                }
            }
            animator.start()
        } else {
            supportFragmentManager.beginTransaction()
                .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                .replace(R.id.fragment_holder, fragment)
                .commit()
        }
    }
}
