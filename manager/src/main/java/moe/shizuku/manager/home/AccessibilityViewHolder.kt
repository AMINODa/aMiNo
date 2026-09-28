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
    }

    private val stateButton: MaterialButton = binding.accessibilityState

    init {
        binding.button1.setOnClickListener { v ->
            // official system page only - never programmatic enabling
            SettingsPage.Accessibility.launch(v.context)
        }
        stateButton.setOnClickListener { v ->
            // tapping the state chip also opens the official settings page
            SettingsPage.Accessibility.launch(v.context)
        }
    }

    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
        val context = stateButton.context
        val enabled = isServiceEnabled(context)

        if (enabled) {
            stateButton.text = context.getString(R.string.accessibility_state_active)
            stateButton.setTextColor(0xFF69F0AE.toInt())
            stateButton.backgroundTintList = ColorStateList.valueOf(0xFF122614.toInt())
        } else {
            stateButton.text = context.getString(R.string.accessibility_state_disabled)
            stateButton.setTextColor(0xFF9E9E9E.toInt())
            stateButton.backgroundTintList = ColorStateList.valueOf(0xFF232323.toInt())
        }
    }
}
