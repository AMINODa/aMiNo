package moe.shizuku.manager.home

import android.content.Context
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.databinding.HomeConnectionStatesBinding
import moe.shizuku.manager.shell.ShellSession
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * aMiNo r1372 - one card, three clearly SEPARATED states:
 *
 *  1. Device PAIRED        -> the ADB key exists in the app's keystore
 *                             (proof of a successful pairing at some point)
 *  2. Wireless debugging   -> read LIVE from Settings.Global (adb_wifi_enabled),
 *                             never claimed by aMiNo - Android owns this toggle
 *  3. Shell session ONLINE -> the actual live ADB connection owned by ShellSession
 *
 * Pairing alone does not imply wireless debugging is on, and neither of the two
 * implies a live shell session. Every bind re-reads the real values.
 */
class ConnectionStateViewHolder(private val binding: HomeConnectionStatesBinding, root: View) :
    BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val binding = HomeConnectionStatesBinding.inflate(inflater, parent, false)
            ConnectionStateViewHolder(binding, binding.root)
        }

        fun isPaired(context: Context): Boolean =
            ShizukuSettings.getPreferences().contains("adbkey")

        fun isWirelessDebugEnabled(context: Context): Boolean =
            runCatching {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            }.getOrDefault(false)
    }

    override fun onBind(payloads: MutableList<Any>) {
        super.onBind(payloads)
        val context = binding.root.context

        val paired = isPaired(context)
        bindState(binding.statePairedValue, paired)

        val wadb = isWirelessDebugEnabled(context)
        bindState(binding.stateWadbValue, wadb)

        val shell = ShellSession.isConnected
        bindState(binding.stateShellValue, shell)
    }

    private fun bindState(view: android.widget.TextView, on: Boolean) {
        val context = view.context
        view.text = if (on) context.getString(R.string.connection_state_on)
        else context.getString(R.string.connection_state_off)
        view.setTextColor(if (on) 0xFF69F0AE.toInt() else 0xFF9E9E9E.toInt())
    }
}
