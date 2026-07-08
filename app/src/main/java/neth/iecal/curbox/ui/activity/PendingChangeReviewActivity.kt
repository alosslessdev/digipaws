package neth.iecal.curbox.ui.activity

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.SettingsChangeDelayUtils

/**
 * Warning screen the settings change delay gate pops right after it parks a change that
 * weakens a rule. It gives the user one clear moment to catch an accidental change: undo it
 * on the spot, or let it wait out the countdown. Dismissing it keeps the change waiting.
 */
class PendingChangeReviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_APPLIES_AT_MS = "applies_at_ms"
        const val EXTRA_REPLACED_EXISTING = "replaced_existing"
    }

    private var dialog: AlertDialog? = null
    private var fieldName = ""
    private var appliesAtMs = 0L
    private var replacedExisting = false

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            if (appliesAtMs - System.currentTimeMillis() <= 0) {
                finish()
                return
            }
            dialog?.setMessage(buildMessage())
            ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readExtras(intent)
        showDialog()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readExtras(intent)
        dialog?.setMessage(buildMessage())
    }

    private fun readExtras(intent: Intent) {
        fieldName = intent.getStringExtra(EXTRA_FIELD) ?: ""
        appliesAtMs = intent.getLongExtra(EXTRA_APPLIES_AT_MS, 0L)
        replacedExisting = intent.getBooleanExtra(EXTRA_REPLACED_EXISTING, false)
    }

    private fun buildMessage(): String {
        val remaining = SettingsChangeDelayUtils.formatRemaining(this, appliesAtMs - System.currentTimeMillis())
        val label = SettingsChangeDelayUtils.fieldLabel(this, fieldName)
        var message = getString(R.string.change_delay_review_message, label, remaining)
        if (replacedExisting) {
            message += "\n\n" + getString(R.string.change_delay_review_replaced_note)
        }
        return message
    }

    private fun showDialog() {
        dialog?.dismiss()
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.change_delay_review_title)
            .setMessage(buildMessage())
            .setPositiveButton(R.string.change_delay_review_keep) { _, _ -> finish() }
            .setNegativeButton(R.string.change_delay_review_undo) { _, _ -> undoChange() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun undoChange() {
        val field = fieldName
        lifecycleScope.launch {
            try {
                DataStoreManager(applicationContext).cancelPendingSettingsChange(field)
                Toast.makeText(applicationContext, R.string.change_delay_cancelled_toast, Toast.LENGTH_SHORT).show()
            } catch (_: Exception) {
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tickRunnable)
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tickRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        ticker.removeCallbacks(tickRunnable)
        dialog?.dismiss()
        dialog = null
    }
}
