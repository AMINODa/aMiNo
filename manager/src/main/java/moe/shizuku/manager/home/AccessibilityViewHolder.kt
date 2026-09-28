package moe.shizuku.manager.home

import android.content.Context
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.button.MaterialButton
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingAccessibilityService
import moe.shizuku.manager.databinding.HomeAccessibilityBinding
import moe.shizuku.manager.databinding.HomeItemContainerBinding
import moe.shizuku.manager.utils.SettingsPage
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * Home card showing the REAL system state of aMiNo's accessibility service.
 *
 * The state is never stored in-app: every bind re-reads
 * Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES from Android. Because
 * HomeAdapter.updateData() (clear + notifyDataSetChanged) runs on every
 * HomeActivity.onResume(), the chip refreshes automatically when the user
 * returns from the system accessibility settings.
 *
 * aMiNo never enables the service silently - the button always routes the
 * user to the official system settings page.
 */
class AccessibilityViewHolder(private val binding: HomeAccessibilityBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR =
            Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeAccessibilityBinding.inflate(inflater, outer.root, true)
                AccessibilityViewHolder(inner, outer.root)
            }

        /** Flattened ComponentName of aMiNo's accessibility service. */
        fun serviceFlattened(context: Context): String =
            "${context.packageName}/${AdbPairingAccessibilityService::class.java.canonicalName}"

        /**
         * TRUE real state, straight from the system - no cached boolean anywhere.
         */
        fun isServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val self = serviceFlattened(context)
            return enabled.split(":").any {
                it.equals(self, ignoreCase = false) || it.endsWith(self.substringAfter('/'))
            }
        }

        /**
         * aMiNo r1372: set ONLY while the user is on the official settings page.
         * Never used as proof of enablement - the chip always renders the REAL
         * system state on every bind (blue iff the system says enabled).
         */
        @Volatile
        var pendingEnablement: Boolean = false
    }

    private val stateButton: MaterialButton = binding.accessibilityState

    init {
        binding.button1.setOnClickListener { v ->
            // official system page only - never programmatic enabling
            pendingEnablement = true
            SettingsPage.Accessibility.launch(v.context)
        }
        stateButton.setOnClickListener { v ->
            // tapping the state chip also opens the official settings page
            pendingEnablement = true
            SettingsPage.Accessibility.launch(v.context)
        }
    }

    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
        val context = stateButton.context
        val enabled = isServiceEnabled(context)

        // ONLY two real states, straight from the system, per the r1372 contract:
        //   BLUE  = service really enabled (read from Settings.Secure NOW)
        //   GRAY  = service not enabled
        // Nothing in between: opening the settings page never colors anything,
        // and no in-app flag is ever treated as proof.
        if (enabled) {
            stateButton.text = context.getString(R.string.accessibility_state_active)
            stateButton.setTextColor(0xFF448AFF.toInt())
            stateButton.backgroundTintList = ColorStateList.valueOf(0xFF0D1B33.toInt())
        } else {
            stateButton.text = context.getString(R.string.accessibility_state_disabled)
            stateButton.setTextColor(0xFF9E9E9E.toInt())
            stateButton.backgroundTintList = ColorStateList.valueOf(0xFF232323.toInt())
        }

        // one real-state render consumed the "waiting" marker
        pendingEnablement = false
    }
}
