package neth.iecal.curbox.ui.fragments.main.reducers.sync

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.sync.SyncBillingStatus
import neth.iecal.curbox.data.sync.SyncGateway
import neth.iecal.curbox.data.sync.SyncStatus

/**
 * Drives the whole account experience over a view_account layout: sign in, sign
 * up, the emailed code, password reset, the secret phrase, and pairing. Shared
 * by the standalone Sync screen and the onboarding step so both look and behave
 * the same.
 */
class AccountController(
    private val root: View,
    private val fragment: Fragment,
    private val requestScan: () -> Unit,
) {
    private enum class Mode { SIGN_IN, SIGN_UP, FORGOT, RESET }

    private companion object {
        // How long the pairing QR stays on screen before it hides itself.
        const val PAIRING_CODE_VISIBLE_MS = 60_000L
    }

    private val provider get() = SyncGateway.provider

    private var mode = Mode.SIGN_IN
    private var last = SyncStatus()
    private var lastBilling = SyncBillingStatus()

    private val title = root.findViewById<TextView>(R.id.text_title)
    private val subtitle = root.findViewById<TextView>(R.id.text_subtitle)
    private val message = root.findViewById<TextView>(R.id.text_message)

    private val email = root.findViewById<TextInputEditText>(R.id.input_email)
    private val password = root.findViewById<TextInputEditText>(R.id.input_password)
    private val code = root.findViewById<TextInputEditText>(R.id.input_code)
    private val forgotEmail = root.findViewById<TextInputEditText>(R.id.input_forgot_email)
    private val resetCode = root.findViewById<TextInputEditText>(R.id.input_reset_code)
    private val newPassword = root.findViewById<TextInputEditText>(R.id.input_new_password)
    private val passphrase = root.findViewById<TextInputEditText>(R.id.input_passphrase)
    private val pairInput = root.findViewById<TextInputEditText>(R.id.input_pair)

    private val sectionPaywall = root.findViewById<View>(R.id.section_paywall)
    private val paywallPrice = root.findViewById<TextView>(R.id.text_paywall_price)
    private val sectionAuth = root.findViewById<View>(R.id.section_auth)
    private val sectionVerify = root.findViewById<View>(R.id.section_verify)
    private val sectionForgot = root.findViewById<View>(R.id.section_forgot)
    private val sectionReset = root.findViewById<View>(R.id.section_reset)
    private val sectionPassphrase = root.findViewById<View>(R.id.section_passphrase)
    private val pairingBlock = root.findViewById<View>(R.id.pairing_block)
    private val sectionUnlocked = root.findViewById<View>(R.id.section_unlocked)

    private val primaryAuth = root.findViewById<MaterialButton>(R.id.btn_primary_auth)
    private val togglePrompt = root.findViewById<TextView>(R.id.text_toggle_prompt)
    private val toggleMode = root.findViewById<TextView>(R.id.btn_toggle_mode)
    private val passphraseBtn = root.findViewById<MaterialButton>(R.id.btn_passphrase)
    private val qr = root.findViewById<ImageView>(R.id.image_qr)

    fun bind() {
        primaryAuth.setOnClickListener {
            if (mode == Mode.SIGN_UP) {
                submit { provider.signUp(text(email), text(password)) }
            } else {
                submit { provider.signIn(text(email), text(password)) }
            }
        }
        toggleMode.setOnClickListener {
            mode = if (mode == Mode.SIGN_IN) Mode.SIGN_UP else Mode.SIGN_IN
            apply()
        }
        root.findViewById<View>(R.id.btn_forgot).setOnClickListener { mode = Mode.FORGOT; apply() }
        root.findViewById<View>(R.id.btn_forgot_back).setOnClickListener { mode = Mode.SIGN_IN; apply() }

        root.findViewById<View>(R.id.btn_verify).setOnClickListener {
            val e = last.pendingEmail ?: return@setOnClickListener
            submit { provider.verifySignupCode(e, text(code)) }
        }
        root.findViewById<View>(R.id.btn_resend).setOnClickListener {
            val e = last.pendingEmail ?: return@setOnClickListener
            submit(fragment.getString(R.string.account_msg_new_code_sent, e)) { provider.resendSignupCode(e) }
        }
        root.findViewById<View>(R.id.btn_send_reset).setOnClickListener {
            val e = text(forgotEmail)
            submit(fragment.getString(R.string.account_msg_reset_code_sent, e)) { provider.sendPasswordReset(e); resetEmail = e; mode = Mode.RESET; apply() }
        }
        root.findViewById<View>(R.id.btn_set_password).setOnClickListener {
            submit { provider.resetPassword(resetEmail, text(resetCode), text(newPassword)) }
        }

        passphraseBtn.setOnClickListener {
            val phrase = text(passphrase)
            if (!last.hasVault && phrase.length < 8) {
                Toast.makeText(fragment.requireContext(), R.string.account_msg_phrase_too_short, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            submit { if (last.hasVault) provider.unlock(phrase) else provider.setPassphrase(phrase) }
        }
        root.findViewById<View>(R.id.btn_pair).setOnClickListener { submit { provider.pairWithCode(text(pairInput)) } }
        root.findViewById<View>(R.id.btn_scan).setOnClickListener { requestScan() }
        root.findViewById<View>(R.id.btn_show_code).setOnClickListener {
            // The code contains the raw secret key, so the user confirms first and
            // the QR takes itself down instead of sitting on screen.
            com.google.android.material.dialog.MaterialAlertDialogBuilder(fragment.requireContext())
                .setTitle(R.string.pairing_code_warning_title)
                .setMessage(R.string.pairing_code_warning_message)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.pairing_code_show) { _, _ -> showPairingCode() }
                .show()
        }
        root.findViewById<View>(R.id.btn_force_sync).setOnClickListener {
            submit(fragment.getString(R.string.account_msg_syncing)) { provider.pushNow(); provider.refresh() }
        }
        root.findViewById<View>(R.id.btn_signout).setOnClickListener { submit { provider.signOut() } }

        root.findViewById<View>(R.id.btn_subscribe).setOnClickListener {
            provider.launchBillingFlow(fragment.requireActivity())
        }
        root.findViewById<View>(R.id.btn_restore_purchase).setOnClickListener {
            provider.refreshBilling()
            Toast.makeText(fragment.requireContext(), R.string.sync_paywall_checking, Toast.LENGTH_SHORT).show()
        }

        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.status.collect { last = it; apply() }
            }
        }
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            fragment.viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                provider.billing.collect { lastBilling = it; apply() }
            }
        }
        // Purchases made or refunded outside the app show up on the next check.
        provider.refreshBilling()
        apply()
    }

    fun pairWith(payload: String) = submit { provider.pairWithCode(payload) }

    private var resetEmail = ""

    private fun apply() {
        val signedIn = last.signedIn
        val unlocked = last.unlocked
        val verifying = !signedIn && last.pendingEmail != null

        // Sync is the one paid feature. When this build sells it and there is no
        // active subscription, the paywall replaces the whole account flow.
        val needsSubscription = lastBilling.required && !lastBilling.entitled
        sectionPaywall.visibility = vis(needsSubscription)
        if (needsSubscription) {
            paywallPrice.text = fragment.getString(
                R.string.sync_paywall_price,
                lastBilling.price ?: fragment.getString(R.string.sync_paywall_price_fallback),
            )
            sectionAuth.visibility = View.GONE
            sectionForgot.visibility = View.GONE
            sectionReset.visibility = View.GONE
            sectionVerify.visibility = View.GONE
            sectionPassphrase.visibility = View.GONE
            pairingBlock.visibility = View.GONE
            sectionUnlocked.visibility = View.GONE
            qr.visibility = View.GONE
            return
        }

        sectionAuth.visibility = vis(!signedIn && !verifying && (mode == Mode.SIGN_IN || mode == Mode.SIGN_UP))
        sectionForgot.visibility = vis(!signedIn && !verifying && mode == Mode.FORGOT)
        sectionReset.visibility = vis(!signedIn && !verifying && mode == Mode.RESET)
        sectionVerify.visibility = vis(verifying)
        sectionPassphrase.visibility = vis(signedIn && !unlocked)
        // Pairing only makes sense once a passphrase already exists on another
        // device. On the very first device there is nothing to pair with, so it
        // would only confuse. Hide it until a vault exists.
        pairingBlock.visibility = vis(signedIn && !unlocked && last.hasVault)
        sectionUnlocked.visibility = vis(unlocked)
        if (!unlocked) qr.visibility = View.GONE

        if (mode == Mode.SIGN_UP) {
            primaryAuth.text = fragment.getString(R.string.account_create_account_action)
            togglePrompt.text = fragment.getString(R.string.account_already_have_account)
            toggleMode.text = fragment.getString(R.string.account_sign_in)
        } else {
            primaryAuth.text = fragment.getString(R.string.account_sign_in)
            togglePrompt.text = fragment.getString(R.string.account_new_here)
            toggleMode.text = fragment.getString(R.string.account_create_account)
        }

        if (signedIn && !unlocked) {
            passphraseBtn.text = fragment.getString(if (last.hasVault) R.string.account_unlock_data else R.string.account_turn_on_sync)
        }

        title.text = when {
            unlocked -> fragment.getString(R.string.account_title_sync_on)
            signedIn -> fragment.getString(if (last.hasVault) R.string.account_unlock_data else R.string.account_make_secret_phrase)
            verifying -> fragment.getString(R.string.account_title_check_email)
            mode == Mode.FORGOT -> fragment.getString(R.string.account_title_reset_password)
            mode == Mode.RESET -> fragment.getString(R.string.account_title_choose_new_password)
            mode == Mode.SIGN_UP -> fragment.getString(R.string.account_title_create_account)
            else -> fragment.getString(R.string.account_title_welcome_back)
        }

        subtitle.text = when {
            unlocked -> fragment.getString(R.string.account_sub_sync_on)
            signedIn && last.hasVault -> fragment.getString(R.string.account_sub_unlock)
            signedIn -> fragment.getString(R.string.account_sub_make_phrase)
            verifying -> fragment.getString(R.string.account_sub_verify, last.pendingEmail)
            mode == Mode.FORGOT -> fragment.getString(R.string.account_sub_forgot)
            mode == Mode.RESET -> fragment.getString(R.string.account_sub_reset)
            mode == Mode.SIGN_UP -> fragment.getString(R.string.account_sub_sign_up)
            else -> fragment.getString(R.string.account_sub_sign_in)
        }

        val msg = last.error
        message.visibility = if (msg != null) View.VISIBLE else View.GONE
        if (msg != null) message.text = msg
    }

    private fun vis(show: Boolean) = if (show) View.VISIBLE else View.GONE

    private fun text(field: TextInputEditText): String = field.text?.toString()?.trim().orEmpty()

    private fun showPairingCode() {
        submit {
            val payload = provider.makePairingCode()
            val bmp = BarcodeEncoder().encodeBitmap(payload, BarcodeFormat.QR_CODE, 600, 600)
            qr.setImageBitmap(bmp)
            qr.visibility = View.VISIBLE
            kotlinx.coroutines.delay(PAIRING_CODE_VISIBLE_MS)
            qr.setImageBitmap(null)
            qr.visibility = View.GONE
        }
    }

    private fun submit(toast: String? = null, block: suspend () -> Unit) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                block()
                if (toast != null) Toast.makeText(fragment.requireContext(), toast, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(fragment.requireContext(), friendly(e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    // Turns raw server and network errors into calm, plain language. Keeps the
    // reader unworried and tells them what to do next.
    private fun friendly(raw: String?): String {
        val m = raw?.lowercase() ?: return fragment.getString(R.string.account_err_generic)
        return when {
            "invalid login" in m || ("invalid" in m && "credential" in m) ->
                fragment.getString(R.string.account_err_invalid_login)
            "already registered" in m || "already been registered" in m ->
                fragment.getString(R.string.account_err_already_registered)
            "email not confirmed" in m -> fragment.getString(R.string.account_err_email_not_confirmed)
            "token has expired" in m || "otp" in m || ("invalid" in m && "code" in m) ->
                fragment.getString(R.string.account_err_bad_code)
            "for security purposes" in m || "rate limit" in m || "too many" in m ->
                fragment.getString(R.string.account_err_rate_limit)
            "password should be" in m || "at least" in m && "character" in m ->
                fragment.getString(R.string.account_err_weak_password)
            "unable to resolve host" in m || "failed to connect" in m || "timeout" in m ||
                "network" in m || "no address associated" in m ->
                fragment.getString(R.string.account_err_no_network)
            else -> raw ?: fragment.getString(R.string.account_err_generic)
        }
    }
}
