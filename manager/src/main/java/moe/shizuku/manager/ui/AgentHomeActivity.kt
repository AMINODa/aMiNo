package moe.shizuku.manager.ui

import android.content.Intent
import android.widget.Toast
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.chip.Chip
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import moe.shizuku.manager.R
import moe.shizuku.manager.agent.AgentOrchestrator
import moe.shizuku.manager.agent.AgentStatus
import moe.shizuku.manager.agent.auto.AutonomousEngine
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.app.AppActivity
import moe.shizuku.manager.keys.ApiKeysStore
import moe.shizuku.manager.databinding.ActivityAgentHomeBinding
import moe.shizuku.manager.settings.SettingsActivity
import moe.shizuku.manager.shell.ShellActivity

/**
 * AMINO Agent chat - the new home screen (r1376). The conversation is the center of
 * the app; the classic home (service status cards, pairing/ADB) stays reachable from
 * the drawer as "Adb" — opened via its concrete subclass MainActivity (1.1.1: the
 * abstract HomeActivity itself can never be an intent target).
 */
open class AgentHomeActivity : AppActivity() {

    protected lateinit var binding: ActivityAgentHomeBinding
    private val adapter = ChatAdapter()

    // r1379: autonomous task mode (architecture C) — toggled by the ⚡ Auto button
    private var autoMode = false
    private var confirmDialog: androidx.appcompat.app.AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAgentHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.chatList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        binding.chatList.adapter = adapter

        binding.toolbar.inflateMenu(R.menu.menu_agent_home)
        binding.toolbar.setOnMenuItemClickListener { mi ->
            when (mi.itemId) {
                // 1.5.0-alpha r1412 — THE one-tap Sharingan button (user request:
                // "زر واحد عند الضغط تظهر اللوحة العائمة"). Uses the CONNECTED
                // accessibility service as the overlay window token; if the
                // service is off, guide instead of failing silently.
                R.id.action_sharingan -> {
                    val svc = moe.shizuku.manager.sharingan.SharinganAccessibilityService.instance
                    if (svc != null) {
                        moe.shizuku.manager.sharingan.SharinganPanel.toggle(svc)
                        Toast.makeText(this, R.string.sharingan_toast_panel, Toast.LENGTH_SHORT).show()
                    } else {
                        androidx.appcompat.app.AlertDialog.Builder(this)
                            .setTitle(R.string.sharingan_need_enable_title)
                            .setMessage(R.string.sharingan_need_enable_msg)
                            .setPositiveButton(R.string.sharingan_btn_enable) { _, _ ->
                                startActivity(
                                    Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                )
                            }
                            .setNegativeButton(R.string.sharingan_btn_later, null)
                            .show()
                    }
                    true
                }
                R.id.action_new_chat -> { AgentOrchestrator.newConversation(this); true }
                // 1.1.1 — HomeActivity is ABSTRACT (never instantiable): launching it
                // directly threw InstantiationException and killed the process. The
                // concrete manifest-declared subclass MainActivity (the classic home
                // UI, launcher of r1378–r1401) is the correct target.
                R.id.action_service_status -> { startActivity(Intent(this, MainActivity::class.java)); true }
                R.id.action_settings -> { startActivity(Intent(this, SettingsActivity::class.java)); true }
                else -> false
            }
        }

        binding.sendBtn.setOnClickListener {
            val text = binding.inputEdit.text?.toString().orEmpty()
            if (text.isBlank()) return@setOnClickListener
            binding.inputEdit.setText("")
            if (autoMode) AutonomousEngine.run(this, text)
            else AgentOrchestrator.send(this, text)
        }
        binding.stopBtn.setOnClickListener {
            AgentOrchestrator.cancel()
            AutonomousEngine.cancel()
        }
        binding.autoBtn.setOnClickListener {
            autoMode = !autoMode
            binding.autoBtn.isChecked = autoMode
            // r1381: ChatGPT-style pill — dark grey idle, aMiNo red active
            binding.autoBtn.setTextColor(if (autoMode) 0xFFFFFFFF.toInt() else 0xFFFF867C.toInt())
            binding.autoBtn.backgroundTintList =
                android.content.res.ColorStateList.valueOf(if (autoMode) 0xFFD32F2F.toInt() else 0xFF1A1A20.toInt())
            binding.inputEdit.hint = getString(if (autoMode) R.string.auto_hint else R.string.agent_hint)
        }

        // r1381: empty-state suggestion chips fill the composer
        val suggestions = mapOf(
            binding.suggest1 to R.string.agent_suggest_1,
            binding.suggest2 to R.string.agent_suggest_2,
            binding.suggest3 to R.string.agent_suggest_3,
            binding.suggest4 to R.string.agent_suggest_4
        )
        for ((view, res) in suggestions) {
            view.setOnClickListener {
                if (binding.inputEdit.isEnabled) {
                    binding.inputEdit.setText(getString(res))
                    binding.inputEdit.requestFocus()
                }
            }
        }
        binding.statusChip.setOnClickListener {
            if (AgentOrchestrator.state.value.status is AgentStatus.NotConfigured || !ApiKeysStore.hasKey(this)) {
                startActivity(Intent(this, moe.shizuku.manager.keys.KeysActivity::class.java))
            }
        }

        // r1382: side drawer navigation (replaces the bottom nav bar)
        binding.toolbar.setNavigationOnClickListener {
            binding.drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
        }
        fun drawerLabel(v: TextView, res: Int, emoji: String) {
            v.text = "$emoji ${getString(res)}"
        }
        drawerLabel(binding.dChat, R.string.nav_chat, "💬")
        // aMiNo 1.1 — the classic home (pairing/service/wireless-debugging) is no
        // longer the launcher: it lives here in the drawer, named "Adb" (user request)
        // 1.1.1 — target MainActivity (concrete), NOT abstract HomeActivity
        drawerLabel(binding.dHome, R.string.nav_adb, "📡")
        drawerLabel(binding.dKeys, R.string.nav_keys, "🔑")
        drawerLabel(binding.dMemory, R.string.nav_memory, "🧠")
        drawerLabel(binding.dTools, R.string.nav_tools, "🧰")
        // 1.4 — Skills Center: install/import/organize/compose skills (drawer 🧩)
        drawerLabel(binding.dSkills, R.string.nav_skills, "🧩")
        drawerLabel(binding.dTerminal, R.string.terminal_title, "⌨️")
        drawerLabel(binding.dShell, R.string.nav_shell, "🖥️")
        drawerLabel(binding.dPermissions, R.string.menu_permissions, "🔐")
        drawerLabel(binding.dStatus, R.string.nav_service_status, "ℹ️")
        drawerLabel(binding.dSettings, R.string.settings_title, "⚙️")
        // the chat is the current page — glow red
        binding.dChat.setTextColor(0xFFFF5252.toInt())
        binding.dChat.setTypeface(null, android.graphics.Typeface.BOLD)

        val open = { v: View, target: Class<*> ->
            v.setOnClickListener {
                binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START)
                startActivity(Intent(this, target))
            }
        }
        // 1.1.1 — was HomeActivity::class.java (ABSTRACT): tapping "Adb" (or the old
        // "Pairing" item) crashed with InstantiationException. MainActivity is the
        // concrete HomeActivity subclass declared in the manifest — same UI.
        open(binding.dHome, MainActivity::class.java)
        open(binding.dKeys, moe.shizuku.manager.keys.KeysActivity::class.java)
        open(binding.dMemory, moe.shizuku.manager.memory.MemoryActivity::class.java)
        open(binding.dTools, moe.shizuku.manager.tools.ToolsActivity::class.java)
        open(binding.dSkills, moe.shizuku.manager.skills.SkillsActivity::class.java)
        open(binding.dTerminal, moe.shizuku.manager.ui.TerminalActivity::class.java)
        open(binding.dShell, ShellActivity::class.java)
        open(binding.dPermissions, AllPermissionsActivity::class.java)
        open(binding.dStatus, MainActivity::class.java)
        open(binding.dSettings, SettingsActivity::class.java)
        binding.dChat.setOnClickListener { binding.drawerLayout.closeDrawer(androidx.core.view.GravityCompat.START) }
        // r1383: was smoothScrollToPosition(itemCount - 1) — with an empty conversation this
        // scrolled to position -1 and crashed the app on EVERY page open
        // (IllegalArgumentException: Invalid target position). Now guarded.
        binding.dChat.post { scrollChatToBottom() }

        // open a specific conversation when coming from the Memory page
        val convId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1)
        if (convId > 0) AgentOrchestrator.openConversation(this, convId) else AgentOrchestrator.loadLatestOrNew(this)

        // aMiNo 1.1 — FIRST LAUNCH: the Agent greets the user IN the chat with the
        // four onboarding steps (pairing/ADB → permissions → Linux env → AI key).
        // Replaces the r1382 auto-open of AllPermissionsActivity — that window is
        // still reachable from the drawer ("Permissions"), nothing pops on launch.
        // Shown exactly once per install (fresh install or upgrade lands here once).
        val prefs = getSharedPreferences("amino_agent", MODE_PRIVATE)
        if (!prefs.getBoolean("welcome_done", false)) {
            prefs.edit().putBoolean("welcome_done", true).apply()
            AgentOrchestrator.postWelcome(this, getString(R.string.welcome_body))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AgentOrchestrator.state.collect { render(it) }
            }
        }

        // r1379: autonomous engine state -> chip phase + approval dialog
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AutonomousEngine.state.collect { auto ->
                    val busy = s_busy || auto.busy
                    binding.stopBtn.visibility =
                        if (busy) android.view.View.VISIBLE else android.view.View.GONE
                    // r1381: one circular slot — send swaps to stop while busy (ChatGPT style)
                    binding.sendBtn.visibility =
                        if (busy) android.view.View.GONE else android.view.View.VISIBLE
                    binding.sendBtn.isEnabled = !busy
                    binding.inputEdit.isEnabled = !busy
                    val pending = auto.awaiting
                    if (pending != null) showConfirmDialog(pending.command, pending.reason)
                    else confirmDialog?.dismiss()
                }
            }
        }
    }

    /**
     * r1383 crash fix (user report: IllegalArgumentException "Invalid target position"
     * on Android 13 when pressing the Agent button). RecyclerView's smooth scroller
     * throws if the target position does not exist — e.g. position -1 on an empty
     * conversation (fresh install, new chat, or history still loading). Every scroll
     * of the chat list now goes through this guard: no items, no scroll.
     */
    private fun scrollChatToBottom(smooth: Boolean = true) {
        val n = adapter.itemCount
        if (n <= 0) return
        if (smooth) binding.chatList.smoothScrollToPosition(n - 1)
        else binding.chatList.scrollToPosition(n - 1)
    }

    private fun showConfirmDialog(command: String, reason: String) {
        if (confirmDialog?.isShowing == true) return
        confirmDialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_confirm_title))
            .setMessage("$ ${command}\n\n${getString(R.string.auto_confirm_why, reason)}")
            .setPositiveButton(getString(R.string.auto_confirm_allow)) { _, _ -> AutonomousEngine.approve(true) }
            .setNegativeButton(getString(R.string.auto_confirm_deny)) { _, _ -> AutonomousEngine.approve(false) }
            .setOnCancelListener { AutonomousEngine.approve(false) }
            .show()
    }

    private val s_busy: Boolean get() = AgentOrchestrator.state.value.busy

    private fun render(s: moe.shizuku.manager.agent.AgentUiState) {
        val had = adapter.itemCount
        adapter.submit(s.items)
        // r1383: only scroll when there is something to scroll to — an empty list has
        // no valid position and scrolling it throws
        if ((s.items.size != had || s.busy) && s.items.isNotEmpty()) {
            binding.chatList.scrollToPosition((s.items.size - 1).coerceAtLeast(0))
        }

        // r1381: ChatGPT-like empty state (hidden once the conversation has rows or work is running)
        binding.emptyState.visibility =
            if (s.items.isEmpty() && !s.busy) android.view.View.VISIBLE else android.view.View.GONE

        binding.statusChip.text = when (val st = s.status) {
            is AgentStatus.Ready -> if (s.providerReady) getString(R.string.agent_status_ready)
            else getString(R.string.agent_status_not_configured)
            is AgentStatus.Thinking -> getString(R.string.agent_status_thinking)
            is AgentStatus.ExecutingTool -> getString(R.string.agent_status_tool, st.name)
            is AgentStatus.Verifying -> getString(R.string.agent_status_verify)
            is AgentStatus.NotConfigured -> getString(R.string.agent_status_not_configured)
        }
        // r1379: while the autonomous loop runs, the chip shows ITS phase instead
        val autoBusy = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.busy
        if (autoBusy) {
            binding.statusChip.text = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.phase
                ?: getString(R.string.agent_status_thinking)
        }
        val autoPending = moe.shizuku.manager.agent.auto.AutonomousEngine.state.value.awaiting != null
        val busyAll = s.busy || autoBusy
        binding.stopBtn.visibility = if (busyAll) android.view.View.VISIBLE else android.view.View.GONE
        binding.sendBtn.visibility = if (busyAll) android.view.View.GONE else android.view.View.VISIBLE
        binding.sendBtn.isEnabled = !busyAll
        binding.inputEdit.isEnabled = !busyAll
        if (autoPending) return
    }

    companion object {
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
